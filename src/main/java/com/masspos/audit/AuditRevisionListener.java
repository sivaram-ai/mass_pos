package com.masspos.audit;

import com.masspos.common.persistence.UuidV7;
import com.masspos.config.PosProperties;
import org.hibernate.envers.RevisionListener;

/**
 * Stamps each revision with its terminal and acting user. Hibernate obtains this listener through
 * Spring's bean container (auto-configured by Spring Boot), which is what makes constructor
 * injection work here.
 */
public class AuditRevisionListener implements RevisionListener {

    private final PosProperties pos;

    public AuditRevisionListener(PosProperties pos) {
        this.pos = pos;
    }

    @Override
    public void newRevision(Object revisionEntity) {
        ((AuditRevision) revisionEntity).stamp(UuidV7.next(), pos.terminal().code(), AuditContext.currentActor());
    }
}
