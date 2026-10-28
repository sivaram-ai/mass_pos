package com.masspos.audit;

import com.masspos.client.CurrentCounter;
import com.masspos.common.persistence.UuidV7;
import org.hibernate.envers.RevisionListener;

/**
 * Stamps each revision with its counter and acting user. Hibernate obtains this listener through
 * Spring's bean container (auto-configured by Spring Boot), which is what makes constructor
 * injection work here.
 *
 * <p>The counter is the machine the change was made on, not the machine the database lives on:
 * an audit trail that said every change in the shop happened on the server would answer none of
 * the questions it exists to answer.
 */
public class AuditRevisionListener implements RevisionListener {

    private final CurrentCounter counter;

    public AuditRevisionListener(CurrentCounter counter) {
        this.counter = counter;
    }

    @Override
    public void newRevision(Object revisionEntity) {
        ((AuditRevision) revisionEntity).stamp(UuidV7.next(), counter.code(), AuditContext.currentActor());
    }
}
