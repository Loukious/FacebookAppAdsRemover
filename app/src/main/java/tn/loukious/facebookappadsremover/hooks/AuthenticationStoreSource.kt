package tn.loukious.facebookappadsremover.hooks

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

/** Reads Facebook's canonical authentication store, rather than a retained viewer context. */
internal class AuthenticationStoreSource {
    data class Observation(
        val session: AccountCaptureState.Session,
        val snapshot: AccountCaptureState.Snapshot,
    )

    private sealed interface Authority {
        data object Unbound : Authority
        data object Conflicting : Authority
        data class Bound(
            val factory: Any,
            val factoryGetter: Method,
            val prefsClass: Class<*>,
            val snapshotGetter: Method,
        ) : Authority
    }

    @Volatile private var authority: Authority = Authority.Unbound

    /** Only inspect constructor captures here; the factory may still be creating its map entry. */
    @Synchronized
    fun observe(provider: Any, prefsClass: Class<*>): Boolean {
        if (authority === Authority.Conflicting) return false
        val snapshotGetter = prefsClass.methods.filter {
            !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                Map::class.java.isAssignableFrom(it.returnType)
        }.singleOrNull() ?: return false

        val factories = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val candidates = runCatching {
            instanceFields(provider.javaClass).mapNotNull { field ->
                val factory = value(field, provider) ?: return@mapNotNull null
                val getter = factory.javaClass.declaredMethods.filter {
                    !Modifier.isStatic(it.modifiers) &&
                        it.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                        preferencesInterface(it.returnType, prefsClass)
                }.singleOrNull() ?: return@mapNotNull null
                if (!factories.add(factory)) return@mapNotNull null
                getter.isAccessible = true
                snapshotGetter.isAccessible = true
                Authority.Bound(factory, getter, prefsClass, snapshotGetter)
            }
        }.getOrNull() ?: return false

        if (candidates.size > 1) {
            authority = Authority.Conflicting
            return false
        }
        val candidate = candidates.singleOrNull() ?: return false
        val previous = authority
        if (previous is Authority.Bound) {
            if (previous.factory !== candidate.factory || previous.prefsClass != candidate.prefsClass ||
                previous.factoryGetter != candidate.factoryGetter || previous.snapshotGetter != candidate.snapshotGetter
            ) {
                authority = Authority.Conflicting
                return false
            }
            return true
        }
        authority = candidate
        return true
    }

    /** Late attach: preferences -> holder -> javax.inject.Provider -> captured factory. */
    fun observeStore(store: Any): Boolean {
        val providers = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val found = runCatching {
            instanceFields(store.javaClass).mapNotNull { field ->
                val providerField = instanceFields(field.type).filter {
                    it.type.name == "javax.inject.Provider"
                }.singleOrNull() ?: return@mapNotNull null
                val holder = value(field, store) ?: return@mapNotNull null
                val provider = value(providerField, holder) ?: return@mapNotNull null
                if (providers.add(provider)) provider else null
            }
        }.getOrNull() ?: return false
        if (found.size > 1) {
            synchronized(this) { authority = Authority.Conflicting }
            return false
        }
        if (found.isEmpty()) return false
        return observe(found.single(), store.javaClass)
    }

    /** Invoke outside our observation monitor; a factory call can itself run a constructor hook. */
    fun read(): Observation {
        val bound = authority as? Authority.Bound ?: return unknown()
        val observation = runCatching {
            val store = bound.factoryGetter.invoke(bound.factory, "authentication")
            check(bound.prefsClass.isInstance(store))
            val snapshot = bound.snapshotGetter.invoke(store) as? Map<*, *>
                ?: return@runCatching unknown()
            parse(snapshot)
        }.getOrElse { unknown() }
        return if (authority === bound) observation else unknown()
    }

    private fun parse(values: Map<*, *>): Observation {
        for (key in listOf("uid", "access_token")) {
            if (values[key] != null && values[key] !is String) return unknown()
        }
        val userId = values["uid"] as? String
        val token = values["access_token"] as? String
        // AuthDataStorage checks this before interpreting any page/admin data.
        if (userId.isNullOrBlank() || token.isNullOrBlank()) {
            return Observation(AccountCaptureState.Session.LoggedOut, AccountCaptureState.Snapshot())
        }
        if (values.containsKey("page_admin_uid") || values.containsKey("page_admin_access_token")) {
            val adminId = values["page_admin_uid"] as? String ?: return unknown()
            val adminToken = values["page_admin_access_token"] as? String ?: return unknown()
            if (adminId.isBlank() || adminToken.isBlank()) return unknown()
            // Normal optional fields belong to the page identity, not its admin.
            return signedIn(adminToken, adminId, null, null)
        }
        for (key in listOf("username", "session_cookies_string")) {
            if (values[key] != null && values[key] !is String) return unknown()
        }
        return signedIn(
            token, userId,
            (values["username"] as? String)?.takeIf { it.isNotBlank() },
            (values["session_cookies_string"] as? String)?.takeIf { it.isNotBlank() },
        )
    }

    private fun signedIn(token: String, userId: String, username: String?, cookies: String?) = Observation(
        AccountCaptureState.Session.SignedIn(token, userId),
        AccountCaptureState.Snapshot(token, userId, username, cookies),
    )

    private fun unknown() = Observation(AccountCaptureState.Session.Unknown, AccountCaptureState.Snapshot())

    private fun preferencesInterface(type: Class<*>, prefsClass: Class<*>): Boolean =
        type.isInterface && type.isAssignableFrom(prefsClass) && type.methods.any {
            !Modifier.isStatic(it.modifiers) && it.name == "getString" &&
                it.returnType == String::class.java &&
                it.parameterTypes.contentEquals(arrayOf(String::class.java, String::class.java))
        } && type.methods.count {
            !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                Map::class.java.isAssignableFrom(it.returnType)
        } == 1

    private fun instanceFields(type: Class<*>): List<Field> =
        type.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive }

    private fun value(field: Field, owner: Any): Any? {
        field.isAccessible = true
        return field.get(owner)
    }
}
