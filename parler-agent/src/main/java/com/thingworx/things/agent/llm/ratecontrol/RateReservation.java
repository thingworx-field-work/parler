package com.thingworx.things.agent.llm.ratecontrol;

/** Local reservation acquired before upstream HTTP ({@code docs/agent/rate-control.md} §6). */
final class RateReservation {

    final long reservedTokens;
    final int reservedRequests;
    final TokenReserveStrategy strategy;

    RateReservation(long reservedTokens, int reservedRequests, TokenReserveStrategy strategy) {
        this.reservedTokens = reservedTokens;
        this.reservedRequests = reservedRequests;
        this.strategy = strategy;
    }
}
