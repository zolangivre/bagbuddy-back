package com.bagbuddy.transactionservice.dto;

import com.bagbuddy.transactionservice.model.TransactionMessage;

import java.time.LocalDateTime;
import java.util.List;

/** Message tel qu'expose. `mine` evite au front de comparer lui-meme les subs. */
public record TransactionMessageView(Long id, String senderSub, String body, LocalDateTime createdAt,
                                     boolean mine) {

    public static TransactionMessageView of(TransactionMessage message, String callerSub) {
        return new TransactionMessageView(message.getId(), message.getSenderSub(), message.getBody(),
                message.getCreatedAt(), message.getSenderSub().equals(callerSub));
    }

    public static List<TransactionMessageView> of(List<TransactionMessage> messages, String callerSub) {
        return messages.stream().map(message -> of(message, callerSub)).toList();
    }
}
