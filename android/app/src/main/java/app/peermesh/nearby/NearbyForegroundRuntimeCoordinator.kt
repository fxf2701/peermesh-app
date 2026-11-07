package app.peermesh.nearby

import android.content.Context
import app.peermesh.OutboundBatchDispatcher
import app.peermesh.ble.BleMeshRuntime
import app.peermesh.data.debug.ActivityLog
import app.peermesh.transport.adapter.NearbyForegroundDiagnosticLabels
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NearbyForegroundRuntimeStateMachine {
    sealed interface State {
        data object Normal : State
        data object Discovery : State
        data class Joining(val roomId: String, val attemptId: String) : State
        data class ActiveRoom(val roomId: String, val sessionId: String) : State
    }

    data class Transition(
        val previous: State,
        val current: State,
        val changed: Boolean,
        val staleIdentityIgnored: Boolean = false,
    )

    var state: State = State.Normal
        private set

    fun enterDiscovery(): Transition = update {
        when (it) {
            State.Normal -> State.Discovery
            else -> it
        }
    }

    fun leaveDiscovery(): Transition = update {
        if (it == State.Discovery) State.Normal else it
    }

    fun beginJoining(roomId: String, attemptId: String): Transition {
        val previous = state
        val next = when (previous) {
            State.Normal,
            State.Discovery,
            -> State.Joining(roomId, attemptId)
            is State.Joining -> if (previous.roomId == roomId && previous.attemptId == attemptId) previous
            else return Transition(previous, previous, changed = false, staleIdentityIgnored = true)
            is State.ActiveRoom -> return Transition(previous, previous, changed = false, staleIdentityIgnored = true)
        }
        state = next
        return Transition(previous, next, previous != next)
    }

    fun activateRoom(roomId: String, attemptId: String?, sessionId: String): Transition {
        val previous = state
        val next = when (val current = state) {
            is State.Joining -> {
                if (current.roomId == roomId && current.attemptId == attemptId) {
                    State.ActiveRoom(roomId, sessionId)
                } else {
                    return Transition(previous, previous, changed = false, staleIdentityIgnored = true)
                }
            }
            is State.ActiveRoom -> {
                if (current.roomId == roomId) current
                else return Transition(previous, previous, changed = false, staleIdentityIgnored = true)
            }
            State.Normal,
            State.Discovery,
            -> if (attemptId == null) State.ActiveRoom(roomId, sessionId) else {
                return Transition(previous, previous, changed = false, staleIdentityIgnored = true)
            }
        }
        state = next
        return Transition(previous, next, previous != next)
    }

    fun releaseJoining(attemptId: String): Transition {
        val previous = state
        val current = state
        if (current !is State.Joining || current.attemptId != attemptId) {
            return Transition(previous, previous, changed = false, staleIdentityIgnored = true)
        }
        state = State.Normal
        return Transition(previous, state, changed = true)
    }

    fun releaseRoom(sessionId: String): Transition {
        val previous = state
        val current = state
        if (current !is State.ActiveRoom || current.sessionId != sessionId) {
            return Transition(previous, previous, changed = false, staleIdentityIgnored = true)
        }
        state = State.Normal
        return Transition(previous, state, changed = true)
    }

    fun allowsNonNearbyWork(): Boolean = state is State.Normal || state is State.Discovery

    fun acceptsRoomProtocol(roomId: String): Boolean = when (val current = state) {
        is State.Joining -> current.roomId == roomId
        is State.ActiveRoom -> current.roomId == roomId
        else -> false
    }

    private inline fun update(transform: (State) -> State): Transition {
        val previous = state
        val next = transform(previous)
        state = next
        return Transition(previous, next, previous != next)
    }
}

object NearbyForegroundRuntimeCoordinator {
    private val machine = NearbyForegroundRuntimeStateMachine()
    private val joinStartedAtMsByAttemptId = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun currentState(): NearbyForegroundRuntimeStateMachine.State = machine.state

    @Synchronized
    fun enterDiscovery(context: Context, reason: String) {
        publish(context, machine.enterDiscovery(), reason, operation = "enter_discovery")
    }

    @Synchronized
    fun leaveDiscovery(context: Context, reason: String) {
        publish(context, machine.leaveDiscovery(), reason, operation = "leave_discovery")
    }

    @Synchronized
    fun beginJoining(
        context: Context,
        roomId: String,
        reason: String,
        attemptId: String = UUID.randomUUID().toString(),
    ): String {
        val transition = machine.beginJoining(roomId, attemptId)
        if (transition.changed) joinStartedAtMsByAttemptId[attemptId] = System.currentTimeMillis()
        publish(context, transition, reason, operation = "begin_joining", requestedRoomId = roomId, requestedIdentity = attemptId)
        return attemptId
    }

    @Synchronized
    fun activateRoom(
        context: Context,
        roomId: String,
        attemptId: String?,
        reason: String,
        sessionId: String = UUID.randomUUID().toString(),
    ): String? {
        val transition = machine.activateRoom(roomId, attemptId, sessionId)
        publish(context, transition, reason, operation = "promote", requestedRoomId = roomId, requestedIdentity = attemptId ?: sessionId)
        return (transition.current as? NearbyForegroundRuntimeStateMachine.State.ActiveRoom)?.sessionId
            ?.takeIf { (transition.current as NearbyForegroundRuntimeStateMachine.State.ActiveRoom).roomId == roomId }
    }

    @Synchronized
    fun releaseJoining(context: Context, attemptId: String, reason: String) {
        publish(context, machine.releaseJoining(attemptId), reason, operation = "release_joining")
    }

    @Synchronized
    fun releaseRoom(context: Context, sessionId: String, reason: String) {
        publish(context, machine.releaseRoom(sessionId), reason, operation = "release_room")
    }

    @Synchronized
    fun acceptsRoomProtocol(roomId: String): Boolean = machine.acceptsRoomProtocol(roomId)

    @Synchronized
    fun runtimeIdentityForRoom(roomId: String): String? = when (val current = machine.state) {
        is NearbyForegroundRuntimeStateMachine.State.Joining -> current.attemptId.takeIf { current.roomId == roomId }
        is NearbyForegroundRuntimeStateMachine.State.ActiveRoom -> current.sessionId.takeIf { current.roomId == roomId }
        else -> null
    }

    @Synchronized
    fun allowsNearbyWorkerForRoom(roomId: String): Boolean = when (machine.state) {
        NearbyForegroundRuntimeStateMachine.State.Normal,
        NearbyForegroundRuntimeStateMachine.State.Discovery,
        -> true
        is NearbyForegroundRuntimeStateMachine.State.Joining,
        is NearbyForegroundRuntimeStateMachine.State.ActiveRoom,
        -> machine.acceptsRoomProtocol(roomId)
    }

    /** Context-valid workers use null to mean no foreground session, not stale. */
    @Synchronized
    fun isContextWorkerIdentityCurrent(roomId: String, capturedIdentity: String?): Boolean {
        val currentIdentity = runtimeIdentityForRoom(roomId)
        return when {
            capturedIdentity == null && currentIdentity == null -> true
            capturedIdentity != null && capturedIdentity == currentIdentity -> true
            else -> false
        }
    }

    @Synchronized
    fun joinTimingFields(roomId: String): String {
        val state = machine.state
        val identity = runtimeIdentityForRoom(roomId)
        val startedAtMs = identity?.let { joinStartedAtMsByAttemptId[it] } ?: 0L
        val elapsedMs = if (startedAtMs > 0L) System.currentTimeMillis() - startedAtMs else -1L
        return "room_id=$roomId runtime_state=${state.label()} attempt_id=${identity?.take(8) ?: "none"} elapsed_from_attempt_ms=$elapsedMs"
    }

    private fun publish(
        context: Context,
        transition: NearbyForegroundRuntimeStateMachine.Transition,
        reason: String,
        operation: String,
        requestedRoomId: String? = null,
        requestedIdentity: String? = null,
    ) {
        val current = transition.current
        val foreground = current is NearbyForegroundRuntimeStateMachine.State.Joining ||
            current is NearbyForegroundRuntimeStateMachine.State.ActiveRoom
        val identity = when (current) {
            is NearbyForegroundRuntimeStateMachine.State.Joining ->
                "room_id=${current.roomId} attempt=${current.attemptId.take(8)}"
            is NearbyForegroundRuntimeStateMachine.State.ActiveRoom ->
                "room_id=${current.roomId} session=${current.sessionId.take(8)}"
            else -> "room_id=none"
        }
        val action = if (transition.staleIdentityIgnored) "stale_callback_ignored" else "transition"
        val message =
            "operation=$operation previous_state=${transition.previous.label()} next_state=${current.label()} " +
                "$identity requested_room_id=${requestedRoomId ?: "none"} requested_identity=${requestedIdentity?.take(8) ?: "none"} reason=$reason foreground=$foreground"
        ActivityLog.add(
            label = if (transition.staleIdentityIgnored) {
                NearbyForegroundDiagnosticLabels.STALE_IGNORED
            } else {
                NearbyForegroundDiagnosticLabels.TRANSITION
            },
            source = "nearby",
            message = "action=$action $message",
        )
        if (transition.changed) {
            val detailLabel = when {
                current is NearbyForegroundRuntimeStateMachine.State.Joining -> NearbyForegroundDiagnosticLabels.ACQUIRE
                current is NearbyForegroundRuntimeStateMachine.State.ActiveRoom -> NearbyForegroundDiagnosticLabels.PROMOTE
                transition.previous is NearbyForegroundRuntimeStateMachine.State.Joining ||
                    transition.previous is NearbyForegroundRuntimeStateMachine.State.ActiveRoom -> NearbyForegroundDiagnosticLabels.RELEASE
                else -> null
            }
            detailLabel?.let { label ->
                ActivityLog.add(label = label, source = "nearby", message = message)
            }
            if (current is NearbyForegroundRuntimeStateMachine.State.Joining) {
                ActivityLog.add(
                    label = NearbyForegroundDiagnosticLabels.JOIN_ATTEMPT_START,
                    source = "nearby",
                    message = "$identity ${joinTimingFields(current.roomId)} reason=$reason",
                )
            }
            if (transition.previous is NearbyForegroundRuntimeStateMachine.State.Joining &&
                current is NearbyForegroundRuntimeStateMachine.State.Normal
            ) {
                val terminalLabel = if (reason.contains("cancel", ignoreCase = true)) {
                    NearbyForegroundDiagnosticLabels.JOIN_CANCELLED
                } else {
                    NearbyForegroundDiagnosticLabels.JOIN_FAILED
                }
                ActivityLog.add(label = terminalLabel, source = "nearby", message = message)
            }
            BleMeshRuntime.updateNearbyForegroundMode(
                enabled = foreground,
                reason = "${transition.previous.label()}_to_${current.label()}:$reason",
            )
            if (!foreground) {
                OutboundBatchDispatcher.kick(context.applicationContext, reason = "nearby_foreground_release")
            }
        }
    }

    private fun NearbyForegroundRuntimeStateMachine.State.label(): String = when (this) {
        NearbyForegroundRuntimeStateMachine.State.Normal -> "NORMAL"
        NearbyForegroundRuntimeStateMachine.State.Discovery -> "DISCOVERY"
        is NearbyForegroundRuntimeStateMachine.State.Joining -> "JOINING"
        is NearbyForegroundRuntimeStateMachine.State.ActiveRoom -> "ACTIVE_ROOM"
    }
}
