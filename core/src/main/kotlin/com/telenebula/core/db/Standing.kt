package com.telenebula.core.db

/** What an inbound frame's sender is to this device. */
internal enum class Standing {
    CONTACT,
    REQUEST,
    /** a request opened by the frame being handled */
    NEW_REQUEST,
    /** no contact row, and none may be made */
    STRANGER;

    val isContact: Boolean get() = this == CONTACT
}
