package life.mosaic.tensorllm;

import org.junit.Test;
import static org.junit.Assert.*;

public class TurnServiceGateTest {
    @Test public void requestProtectsPauseBeforePromotion(){TurnServiceGate gate=new TurnServiceGate();long id=gate.request();assertTrue(gate.expected());assertFalse(gate.active());assertTrue(gate.promote(id));assertTrue(gate.active());}
    @Test public void oldDestroyCannotCancelNewPendingStart(){TurnServiceGate gate=new TurnServiceGate();long old=gate.request();gate.promote(old);gate.cancel();long next=gate.request();gate.destroyed(old);assertTrue(gate.expected());assertTrue(gate.promote(next));}
    @Test public void oldDestroyCannotCancelNewPromotedStart(){TurnServiceGate gate=new TurnServiceGate();long old=gate.request();gate.promote(old);long next=gate.request();gate.promote(next);gate.destroyed(old);assertTrue(gate.active());}
    @Test public void cancellationRejectsLateStart(){TurnServiceGate gate=new TurnServiceGate();long id=gate.request();gate.cancel();assertFalse(gate.promote(id));assertFalse(gate.expected());}
    @Test public void replacingRequestNeedsItsOwnPromotion(){TurnServiceGate gate=new TurnServiceGate();long old=gate.request();gate.promote(old);long next=gate.request();assertFalse(gate.active());assertFalse(gate.promote(old));assertTrue(gate.promote(next));}
    @Test public void currentServiceDestructionClosesLifetime(){TurnServiceGate gate=new TurnServiceGate();long id=gate.request();gate.promote(id);gate.destroyed(id);assertFalse(gate.expected());assertFalse(gate.active());}
}
