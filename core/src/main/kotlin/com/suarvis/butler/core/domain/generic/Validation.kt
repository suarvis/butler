package com.suarvis.butler.core.domain.generic

import com.suarvis.butler.core.domain.exception.DomainValidationException

/** Domain counterpart of `require`: throws a [DomainValidationException] instead of a JDK exception. */
internal inline fun ensure(condition: Boolean, message: () -> String) {
    if (!condition) throw DomainValidationException(message())
}
