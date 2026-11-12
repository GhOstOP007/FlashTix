
package com.flashtix.controller;

import com.flashtix.service.RazorpayWebhookService;

import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("dev")
@RequestMapping("/api/webhooks/razorpay")
public class RazorpayWebhookController {

    private final RazorpayWebhookService service;

    public RazorpayWebhookController(
            RazorpayWebhookService service) {

        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public RazorpayWebhookService.WebhookReceipt receive(
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,

            @RequestHeader(value = "x-razorpay-event-id", required = false) String eventId,

            @RequestBody byte[] rawBody) {

        return service.receive(
                rawBody,
                signature,
                eventId);
    }
}
