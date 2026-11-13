package tn.loukious.facebookappadsremover.hooks

import java.lang.reflect.Method

/** Tracks successful AMOLED hook registrations; callers serialize installation. */
internal class AmoledMethodRegistry {
    // Reflection can return a fresh Method object on every probe. Method equality
    // includes its declaring class, name, return type and parameters, so overloads
    // and classes from different classloaders remain distinct.
    private val installed = HashSet<Method>()

    /** Returns true only when this call installs a new hook. Failures remain retryable. */
    fun installOnce(method: Method, install: () -> Unit): Boolean {
        if (method in installed) return false
        install()
        installed.add(method)
        return true
    }
}
