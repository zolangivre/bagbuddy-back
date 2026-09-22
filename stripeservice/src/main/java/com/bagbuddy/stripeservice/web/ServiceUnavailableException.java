package com.bagbuddy.stripeservice.web;

/**
 * Un service dont on depend n'a pas repondu : ni une erreur du client, ni une
 * regle metier. On la distingue explicitement pour ne pas la faire passer pour
 * une requete invalide, et pour que le client sache qu'un nouvel essai a du sens.
 */
public class ServiceUnavailableException extends RuntimeException {

    private final String service;

    public ServiceUnavailableException(String service, Throwable cause) {
        super(service + " n'a pas repondu", cause);
        this.service = service;
    }

    public String getService() {
        return service;
    }
}
