package com.opentasker.core.platform

import android.content.Intent
import android.os.Bundle

/**
 * Unparceling a Parcelable or Serializable builds the class it names and runs that class's code.
 * What another app sends is read with the boot class loader, which has only Android's and Java's
 * own classes, so a value naming a class packed in OpenTasker fails to read instead of being built.
 * Set it before anything unparcels: the system hands receivers and activities the app's own loader.
 */
internal object ForeignParcels {
    val classLoader: ClassLoader = checkNotNull(Bundle::class.java.classLoader)

    /** Points [intent]'s extras at [classLoader]. Call it before the first extra is read. */
    fun guard(intent: Intent): Intent = intent.apply { setExtrasClassLoader(classLoader) }

    /**
     * Reads part of what another app sent. A value that can't be read throws a RuntimeException,
     * and one nested deep enough runs out of stack while needing nothing but Android's own classes.
     */
    inline fun <T> read(read: () -> T, unreadable: (Throwable) -> T): T = try {
        read()
    } catch (e: RuntimeException) {
        unreadable(e)
    } catch (e: StackOverflowError) {
        unreadable(e)
    }
}
