package dev.vox.lss.common.processing;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;
import dev.vox.lss.common.PositionUtil;

/** Fixed-size invalidation-queue epochs. Collisions cause conservative misses only. */
public final class LoadedProbeGuard {
    static final int STRIPES = 4096;
    private final AtomicReferenceArray<Object> tokens = new AtomicReferenceArray<>(STRIPES);
    public LoadedProbeGuard() { for (int i=0;i<STRIPES;i++) tokens.set(i,new Object()); }
    static int stripe(String dimension,long position) {
        long mixed=position ^ ((long)dimension.hashCode()*0x9e3779b97f4a7c15L);
        mixed=(mixed^(mixed>>>30))*0xbf58476d1ce4e5b9L;
        mixed=(mixed^(mixed>>>27))*0x94d049bb133111ebL;
        return (int)(mixed^(mixed>>>31))&(STRIPES-1);
    }
    public Capture capture(String dimension,long position,RequestRegistration registration) {
        return new Capture(this,Objects.requireNonNull(dimension),position,
                Objects.requireNonNull(registration),tokens.get(stripe(dimension,position)));
    }
    /** Called under the processor mailbox lock BEFORE its lossless invalidation append. */
    void invalidate(String dimension,long[] positions) {
        for(long position:positions)tokens.set(stripe(dimension,position),new Object());
    }
    boolean current(LoadedColumnData data,String dimension,long position,RequestRegistration registration) {
        Capture capture=data.probeCapture();
        return capture!=null && capture.guard==this && capture.registration==registration
                && !registration.isRetired() && capture.dimension.equals(dimension)
                && capture.position==position && PositionUtil.packPosition(data.cx(),data.cz())==position
                && capture.token==tokens.get(stripe(dimension,position));
    }
    /** Capture BEFORE serialization; attachment never refreshes the captured epoch. */
    public static final class Capture {
        private final LoadedProbeGuard guard;
        private final String dimension;
        private final long position;
        private final RequestRegistration registration;
        private final Object token;
        private Capture(LoadedProbeGuard guard,String dimension,long position,RequestRegistration registration,Object token) {
            this.guard=guard;this.dimension=dimension;this.position=position;this.registration=registration;this.token=token;
        }
        public LoadedColumnData bind(LoadedColumnData data) {
            if(data==null)return null;
            if(data.probeCapture()!=null || PositionUtil.packPosition(data.cx(),data.cz())!=position)
                throw new IllegalArgumentException("probe metadata cannot be rebound or moved");
            return new LoadedColumnData(data.cx(),data.cz(),data.serializedSections(),data.estimatedBytes(),this);
        }
    }
}
