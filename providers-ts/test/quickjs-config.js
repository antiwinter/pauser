// Shared QuickJS runtime limits.
// The JNI engine (quickjs_engine.c) must be kept in sync with MEMORY_LIMIT_BYTES.
// Stack size intentionally differs: JNI uses 0 (OS-enforced) because JNI frames
// consume stack before QuickJS starts; WASM has a clean stack so a fixed limit is fine.
export const MEMORY_LIMIT_BYTES = 32 * 1024 * 1024; // 32 MB
export const WASM_STACK_SIZE_BYTES = 512 * 1024;     // 512 KB
