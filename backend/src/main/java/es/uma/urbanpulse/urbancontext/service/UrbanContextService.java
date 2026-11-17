package es.uma.urbanpulse.urbancontext.service;

import es.uma.urbanpulse.incidents.entity.Incident;
import es.uma.urbanpulse.urbancontext.entity.UrbanContext;
import es.uma.urbanpulse.urbancontext.entity.UrbanContextStatus;
import es.uma.urbanpulse.urbancontext.repository.UrbanContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starts the urban context of a new incident. It only writes a row: no external source is called
 * while the incident is reported (RF26, ADR-0018), and the worker fills it afterwards.
 */
@Service
public class UrbanContextService {

    private final UrbanContextRepository contexts;

    public UrbanContextService(UrbanContextRepository contexts) {
        this.contexts = contexts;
    }

    /**
     * Inserts the {@code PENDING} context of {@code incident}, inside the transaction that reports
     * it: if the report rolls back, so does its context.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createPending(Incident incident) {
        var context = new UrbanContext();
        context.setIncident(incident);
        context.setStatus(UrbanContextStatus.PENDING);
        contexts.save(context);
    }
}
