package app.peermesh.map.application.interaction

import org.junit.Assert.assertEquals
import org.junit.Test

class PersistedLocalMapParticipationProviderM3A2Test {
    @Test fun positiveRemovalWinsOverProfileEvidence() {
        assertEquals(LocalMapParticipation.NotMember, provider(removed = true, profile = true).participationFor("g"))
    }

    @Test fun positiveProfileEvidenceMeansCurrentMember() {
        assertEquals(LocalMapParticipation.CurrentMember, provider(profile = true).participationFor("g"))
    }

    @Test fun missingProfileIsUnknownRatherThanAssumedNonMember() {
        assertEquals(LocalMapParticipation.Unknown, provider().participationFor("g"))
        assertEquals(LocalMapParticipation.Unknown,
            PersistedLocalMapParticipationProvider({ null }, evidence()).participationFor("g"))
    }

    @Test fun evidenceReadFailureIsUnavailable() {
        val source = object : LocalMapMembershipEvidence {
            override fun isRemoved(groupId: String, actorDid: String): Boolean = error("db")
            override fun hasMemberProfile(groupId: String, actorDid: String): Boolean = false
        }
        assertEquals(LocalMapParticipation.Unavailable,
            PersistedLocalMapParticipationProvider({ "me" }, source).participationFor("g"))
    }

    private fun provider(removed: Boolean = false, profile: Boolean = false) =
        PersistedLocalMapParticipationProvider({ "me" }, evidence(removed, profile))

    private fun evidence(removed: Boolean = false, profile: Boolean = false) = object : LocalMapMembershipEvidence {
        override fun isRemoved(groupId: String, actorDid: String) = removed
        override fun hasMemberProfile(groupId: String, actorDid: String) = profile
    }
}
