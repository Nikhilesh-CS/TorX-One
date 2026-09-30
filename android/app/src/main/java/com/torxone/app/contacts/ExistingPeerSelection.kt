package com.torxone.app.contacts

import com.torxone.app.data.entity.ContactEntity

internal data class ExistingPeerCandidate(val contact: ContactEntity, val hasRelationship: Boolean,
    val hasConnection: Boolean, val hasSession: Boolean, val isActive: Boolean,
    val hasConversation: Boolean, val generation: Int) {
    val usable: Boolean get() = hasRelationship && hasConnection && hasSession
}

/** Keep each secure relationship, while choosing a usable lane independently of Room row order. */
internal fun selectExistingPeer(candidates: List<ExistingPeerCandidate>): ContactEntity? = candidates
    .sortedWith(compareByDescending<ExistingPeerCandidate> { it.usable }
        .thenByDescending { it.isActive }.thenByDescending { it.hasConversation }
        .thenByDescending { it.generation }.thenByDescending { it.contact.createdAt }
        .thenBy { it.contact.relationshipId }.thenBy { it.contact.contactId })
    .firstOrNull()?.contact
