package com.metallum.render;

/** Metal indirect argument strides in bytes; also match Sodium's producer layout. */
final class MetalIndirectArguments {
    // MTLDrawPrimitivesIndirectArguments: vertexCount, instanceCount, vertexStart, baseInstance.
    static final int SIZE = 4 * Integer.BYTES;
    // MTLDrawIndexedPrimitivesIndirectArguments: indexCount, instanceCount, indexStart,
    // signed baseVertex, baseInstance. Each field occupies 32 bits.
    static final int INDEXED_SIZE = 5 * Integer.BYTES;

    private MetalIndirectArguments() {}
}
