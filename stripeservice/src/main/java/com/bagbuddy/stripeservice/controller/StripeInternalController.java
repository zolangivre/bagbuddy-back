package com.bagbuddy.stripeservice.controller;

import com.bagbuddy.stripeservice.service.PayoutService;
import com.bagbuddy.stripeservice.service.PayoutService.TransferResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Execution des mouvements d'argent decides par transactionservice, garde par le role realm
 * 'service' (SecurityConfig). En REST, comme les autres endpoints service-a-service : un seul
 * appelant, une seule forme de reponse, et aucune prise pour le jeton de service sur le schema
 * public. Caddy renvoie 404 sur /stripe/internal en production.
 */
@RestController
@RequestMapping("/stripe/internal")
public class StripeInternalController {

    private final PayoutService payoutService;

    public StripeInternalController(PayoutService payoutService) {
        this.payoutService = payoutService;
    }

    @PostMapping("/refunds")
    public RefundResponse refund(@RequestBody RefundRequest body) {
        return new RefundResponse(payoutService.refund(body.transactionId(), body.paymentIntentId(), body.amount()));
    }

    @PostMapping("/transfers")
    public TransferResult transfer(@RequestBody TransferRequest body) {
        return payoutService.transfer(body.transactionId(), body.paymentIntentId(), body.sellerSub(), body.amount());
    }

    public record RefundRequest(Long transactionId, String paymentIntentId, long amount) {
    }

    public record RefundResponse(String refundId) {
    }

    public record TransferRequest(Long transactionId, String paymentIntentId, String sellerSub, long amount) {
    }
}
