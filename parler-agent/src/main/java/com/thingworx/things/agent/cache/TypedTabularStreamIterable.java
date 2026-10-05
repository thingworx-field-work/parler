package com.thingworx.things.agent.cache;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Single-pass {@link Iterable} over a {@link TypedTabularStream}. Does not materialize the full
 * table; batches are pulled on demand. A second {@link #iterator()} call fails fast so callers
 * cannot accidentally build a second index over the probe side.
 */
public final class TypedTabularStreamIterable implements Iterable<TypedRow> {

    private static final int DEFAULT_BATCH = 64;

    private final TypedTabularStream stream;
    private final int batchSize;
    private int iteratorCalls;

    public TypedTabularStreamIterable(TypedTabularStream stream) {
        this(stream, DEFAULT_BATCH);
    }

    public TypedTabularStreamIterable(TypedTabularStream stream, int batchSize) {
        this.stream = Objects.requireNonNull(stream, "stream");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1");
        }
        this.batchSize = batchSize;
    }

    public int iteratorCalls() {
        return iteratorCalls;
    }

    @Override
    public Iterator<TypedRow> iterator() {
        iteratorCalls++;
        if (iteratorCalls > 1) {
            throw new IllegalStateException("TypedTabularStreamIterable supports a single pass only");
        }
        return new StreamIterator(stream, batchSize);
    }

    private static final class StreamIterator implements Iterator<TypedRow> {
        private final TypedTabularStream stream;
        private final int batchSize;
        private Iterator<TypedRow> batchIter = java.util.Collections.emptyIterator();
        private boolean done;
        private Exception failure;

        StreamIterator(TypedTabularStream stream, int batchSize) {
            this.stream = stream;
            this.batchSize = batchSize;
        }

        @Override
        public boolean hasNext() {
            if (failure != null) {
                throw new IllegalStateException("stream read failed", failure);
            }
            if (done) {
                return false;
            }
            while (!batchIter.hasNext()) {
                try {
                    if (stream.exhausted()) {
                        done = true;
                        return false;
                    }
                    TypedBatch batch = stream.readBatch(batchSize);
                    if (batch.size() == 0) {
                        done = true;
                        return false;
                    }
                    batchIter = batch.rows().iterator();
                } catch (Exception e) {
                    failure = e;
                    throw new IllegalStateException("stream read failed", e);
                }
            }
            return true;
        }

        @Override
        public TypedRow next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return batchIter.next();
        }
    }
}
