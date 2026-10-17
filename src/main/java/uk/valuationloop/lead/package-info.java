/**
 * Lead: one {@link uk.valuationloop.lead.AgentLead} per agent per request, the aggregate that delivery, reply,
 * evidence and outcomes all change. Its states change only through {@link uk.valuationloop.lead.LeadTransitions},
 * a switch over the sealed {@link uk.valuationloop.lead.LeadEvent}, so a new event does not compile until its
 * transitions are decided. The row carries @Version (optimistic locking) because several threads touch it.
 */
package uk.valuationloop.lead;
