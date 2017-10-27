package com.questdb.parser.lp;

import com.questdb.misc.Unsafe;
import com.questdb.std.Mutable;
import com.questdb.std.str.ByteSequence;
import com.questdb.std.str.DirectByteCharSequence;
import com.questdb.std.str.SplitByteSequence;

import java.io.Closeable;

public class LineProtoLexer implements Mutable, Closeable {

    private final DirectByteCharSequence dbcs = new DirectByteCharSequence();
    private final DirectByteCharSequence reserveDbcs = new DirectByteCharSequence();
    private final SplitByteSequence sbcs = new SplitByteSequence();

    private int state = LineProtoParser.EVT_MEASUREMENT;
    private boolean escape = false;
    private long rollPtr = 0;
    private int rollCapacity = 0;
    private int rollSize = 0;

    @Override
    public void clear() {
        rollSize = 0;
        escape = false;
        state = LineProtoParser.EVT_MEASUREMENT;
    }

    @Override
    public void close() {
        if (rollPtr > 0) {
            Unsafe.free(rollPtr, rollCapacity);
        }
    }

    public void parse(long lo, int len, LineProtoParser listener) throws LineProtoException {
        long p = lo;
        long hi = lo + len;
        long _lo = p;

        while (p < hi) {
            char c = (char) Unsafe.getUnsafe().getByte(p++);
            if (escape) {
                escape = false;
                continue;
            }

            switch (c) {
                case ',':
                    switch (state) {
                        case LineProtoParser.EVT_MEASUREMENT:
                            listener.onEvent(makeByteSeq(_lo, p), LineProtoParser.EVT_MEASUREMENT);
                            state = LineProtoParser.EVT_TAG_NAME;
                            break;
                        case LineProtoParser.EVT_TAG_VALUE:
                            listener.onEvent(makeByteSeq(_lo, p), LineProtoParser.EVT_TAG_VALUE);
                            state = LineProtoParser.EVT_TAG_NAME;
                            break;
                        case LineProtoParser.EVT_FIELD_VALUE:
                            listener.onEvent(makeByteSeq(_lo, p), LineProtoParser.EVT_FIELD_VALUE);
                            state = LineProtoParser.EVT_FIELD_NAME;
                            break;
                        default:
                            throw LineProtoException.INSTANCE;
                    }
                    break;
                case '=':
                    switch (state) {
                        case LineProtoParser.EVT_TAG_NAME:
                            listener.onEvent(makeByteSeq(_lo, p), LineProtoParser.EVT_TAG_NAME);
                            state = LineProtoParser.EVT_TAG_VALUE;
                            break;
                        case LineProtoParser.EVT_FIELD_NAME:
                            listener.onEvent(makeByteSeq(_lo, p), LineProtoParser.EVT_FIELD_NAME);
                            state = LineProtoParser.EVT_FIELD_VALUE;
                            break;
                        default:
                            throw LineProtoException.INSTANCE;
                    }
                    break;
                case '\\':
                    escape = true;
                    continue;
                case ' ':
                    switch (state) {
                        case LineProtoParser.EVT_MEASUREMENT:
                            listener.onEvent(makeByteSeq(_lo, p), LineProtoParser.EVT_MEASUREMENT);
                            state = LineProtoParser.EVT_FIELD_NAME;
                            break;
                        case LineProtoParser.EVT_TAG_VALUE:
                            listener.onEvent(makeByteSeq(_lo, p), LineProtoParser.EVT_TAG_VALUE);
                            state = LineProtoParser.EVT_FIELD_NAME;
                            break;
                        case LineProtoParser.EVT_FIELD_VALUE:
                            listener.onEvent(makeByteSeq(_lo, p), LineProtoParser.EVT_FIELD_VALUE);
                            state = LineProtoParser.EVT_TIMESTAMP;
                            break;
                        default:
                            throw LineProtoException.INSTANCE;
                    }
                    break;
                case '\n':
                case '\r':
                    switch (state) {
                        case LineProtoParser.EVT_MEASUREMENT:
                            // empty line?
                            break;
                        case LineProtoParser.EVT_TAG_VALUE:
                            listener.onEvent(makeByteSeq(_lo, p), LineProtoParser.EVT_TAG_VALUE);
                            break;
                        case LineProtoParser.EVT_FIELD_VALUE:
                            listener.onEvent(makeByteSeq(_lo, p), LineProtoParser.EVT_FIELD_VALUE);
                            break;
                        case LineProtoParser.EVT_TIMESTAMP:
                            listener.onEvent(makeByteSeq(_lo, p), LineProtoParser.EVT_TIMESTAMP);
                            break;
                        default:
                            throw LineProtoException.INSTANCE;
                    }

                    if (state != LineProtoParser.EVT_MEASUREMENT) {
                        listener.onEvent(null, LineProtoParser.EVT_END);
                        clear();
                    }
                    break;
                default:
                    // normal byte
                    continue;
            }
            _lo = p;
        }

        if (_lo < hi) {
            rollLine(_lo, hi);
        }
    }

    public void parseLast(LineProtoParser listener) throws LineProtoException {
        if (state != LineProtoParser.EVT_MEASUREMENT) {
            parseLast0(listener);
        }
    }

    private ByteSequence makeByteSeq(long _lo, long hi) throws LineProtoException {
        if (rollSize > 0) {
            return makeByteSeq0(_lo, hi);
        }

        if (_lo == hi - 1) {
            throw LineProtoException.INSTANCE;
        }

        return dbcs.of(_lo, hi - 1);
    }

    private ByteSequence makeByteSeq0(long _lo, long hi) {
        ByteSequence sequence;
        if (_lo == hi - 1) {
            sequence = dbcs.of(rollPtr, rollPtr + rollSize);
        } else {
            sequence = sbcs.of(dbcs.of(rollPtr, rollPtr + rollSize), reserveDbcs.of(_lo, hi - 1));
        }
        rollSize = 0;
        return sequence;
    }

    private void parseLast0(LineProtoParser listener) throws LineProtoException {
        if (state == LineProtoParser.EVT_TIMESTAMP) {
            if (rollSize > 0) {
                listener.onEvent(dbcs.of(rollPtr, rollPtr + rollSize), LineProtoParser.EVT_TIMESTAMP);
            }
        } else if (rollSize == 0) {
            throw LineProtoException.INSTANCE;
        }

        switch (state) {
            case LineProtoParser.EVT_TAG_VALUE:
                listener.onEvent(dbcs.of(rollPtr, rollPtr + rollSize), LineProtoParser.EVT_TAG_VALUE);
                break;
            case LineProtoParser.EVT_FIELD_VALUE:
                listener.onEvent(dbcs.of(rollPtr, rollPtr + rollSize), LineProtoParser.EVT_FIELD_VALUE);
                break;
            case LineProtoParser.EVT_TIMESTAMP:
                break;
            default:
                throw LineProtoException.INSTANCE;
        }
        listener.onEvent(null, LineProtoParser.EVT_END);
    }

    private void rollLine(long lo, long hi) {
        int len = (int) (hi - lo);
        int requiredCapacity = rollSize + len;

        if (requiredCapacity > rollCapacity) {
            long p = Unsafe.malloc(requiredCapacity);
            if (rollSize > 0) {
                Unsafe.getUnsafe().copyMemory(rollPtr, p, rollSize);
            }

            if (rollPtr > 0) {
                Unsafe.free(rollPtr, rollCapacity);
            }
            rollPtr = p;
            rollCapacity = requiredCapacity;
        }
        Unsafe.getUnsafe().copyMemory(lo, rollPtr + rollSize, len);
        rollSize = requiredCapacity;
    }
}
