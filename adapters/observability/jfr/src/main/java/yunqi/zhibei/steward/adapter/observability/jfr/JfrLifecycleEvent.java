package yunqi.zhibei.steward.adapter.observability.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;
import jdk.jfr.Timestamp;

@Name("yunqi.zhibei.steward.Lifecycle")
@Label("Middleware Lifecycle")
@Category({"Yunqi Steward", "Lifecycle"})
@Description("A secret-free lifecycle transition forwarded from LifecycleEventBuffer")
@StackTrace(false)
final class JfrLifecycleEvent extends Event {

    @Label("Owner")
    public String owner;

    @Label("Sequence")
    public long sequence;

    @Label("Stage")
    public String stage;

    @Label("Outcome")
    public String outcome;

    @Label("Generation")
    public long generation;

    @Label("Revision")
    public long revision;

    @Label("Lifecycle Start")
    @Timestamp(Timestamp.MILLISECONDS_SINCE_EPOCH)
    public long lifecycleStartedAt;

    @Label("Lifecycle Duration")
    @Timespan(Timespan.NANOSECONDS)
    public long lifecycleDuration;

    @Label("Failure Type")
    public String failureType;
}
