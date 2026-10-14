package io.github.matthewjones372.lark.bank

import io.github.matthewjones372.lark.actor.Behaviour
import io.github.matthewjones372.lark.actor.Delivered

/**
 * [behaviour] given the command inside each one sent reliably, and confirming it once the step returns.
 *
 * `delivered` over a persistent behaviour drops a resent command without a step, so what that step would have sent on
 * is lost if the node crashed after the events were written and before it was sent. Here the entity sees every copy
 * and answers a repeat from its state, by the transfer's id, sending on again whatever the first copy sent.
 */
internal fun <M : Any, S, E> repeatable(behaviour: Behaviour<M, S, E>, unwrap: (M) -> M): Behaviour<M, S, E> =
    Behaviour(
        initial = behaviour.initial,
        step = { ctx, state, message ->
            behaviour.step(this, ctx, state, unwrap(message)).also { (message as? Delivered)?.delivery?.confirm() }
        },
        signal = behaviour.signal,
        start = behaviour.start,
    )
