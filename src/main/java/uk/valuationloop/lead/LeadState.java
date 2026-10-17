package uk.valuationloop.lead;

/**
 * Where one agent's lead is (ARCHITECTURE.md §3).
 *
 * <pre>
 * CREATED -deliver-> DELIVERING(n) -ok-> DELIVERED -reply-> REPLIED -evidence-> EVIDENCED -visit-> VISIT_BOOKED
 *                        |-fail,n<max-> DELIVERING(n+1)    |-deadline-> EXPIRED -late reply-> REPLIED (late=true)
 *                        |-fail,n==max-> DELIVERY_FAILED
 * </pre>
 */
public enum LeadState {
    CREATED,
    DELIVERING,
    DELIVERED,
    REPLIED,
    EVIDENCED,
    VISIT_BOOKED,
    EXPIRED,
    DELIVERY_FAILED
}
