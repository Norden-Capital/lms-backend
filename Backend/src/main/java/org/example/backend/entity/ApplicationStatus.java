package org.example.backend.entity;

/** Where an application stands — see migration V17. */
public enum ApplicationStatus {

    /** Step 1 is saved and step 2 is not finished. */
    ABANDONED,

    /** Was abandoned, but the same person later became a lead: kept as history, out of the Abandoned list. */
    RETURNED,

    /** Step 2 is finished and LeadScout has not answered yet. */
    SENDING,

    /** LeadScout took it: SOLD, CPF, NOT_SOLD or EXISTS. */
    LEAD,

    /** LeadScout refused it, gave no usable answer, or could not be reached. */
    FAILED
}
