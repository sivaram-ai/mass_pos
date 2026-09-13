package com.masspos.common.persistence;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.generator.EventTypeSets;

import java.util.EnumSet;

/** Hibernate side of {@link GeneratedUuidV7}. */
public class UuidV7IdGenerator implements BeforeExecutionGenerator {

    @Override
    public Object generate(SharedSessionContractImplementor session, Object owner, Object currentValue,
                           EventType eventType) {
        return currentValue != null ? currentValue : UuidV7.next();
    }

    @Override
    public EnumSet<EventType> getEventTypes() {
        return EventTypeSets.INSERT_ONLY;
    }

    /** Rows received through sync arrive with their origin id and must keep it. */
    @Override
    public boolean allowAssignedIdentifiers() {
        return true;
    }
}
