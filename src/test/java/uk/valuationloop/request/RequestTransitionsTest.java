package uk.valuationloop.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.valuationloop.request.RequestState.*;
import static uk.valuationloop.request.RequestTransitions.RequestEvent.*;

import org.junit.jupiter.api.Test;

class RequestTransitionsTest {

    @Test
    void happyPath() {
        RequestState s = RECEIVED;
        s = RequestTransitions.next(s, new AgentsMatched());
        assertThat(s).isEqualTo(MATCHED);
        s = RequestTransitions.next(s, new LeadsDispatched());
        assertThat(s).isEqualTo(AWAITING_REPLIES);
        s = RequestTransitions.next(s, new FirstReply());
        assertThat(s).isEqualTo(REPLIES_IN);
        s = RequestTransitions.next(s, new FirstReply());   // a second reply changes nothing
        assertThat(s).isEqualTo(REPLIES_IN);
        s = RequestTransitions.next(s, new SellerSelected());
        assertThat(s).isEqualTo(CLOSED);
    }

    @Test
    void closesWhenEveryLeadIsTerminalEvenWithoutReplies() {
        assertThat(RequestTransitions.next(AWAITING_REPLIES, new AllLeadsTerminal())).isEqualTo(CLOSED);
    }

    @Test
    void cannotSelectBeforeAnyReply() {
        assertThatThrownBy(() -> RequestTransitions.next(AWAITING_REPLIES, new SellerSelected()))
                .isInstanceOf(IllegalStateException.class);
    }
}
