/*
 * quickjs_engine.c  —  JNI bridge to embedded quickjs-ng
 *
 * Threading: ALL exported JNI functions MUST be called from jsDispatcher
 * (Dispatchers.IO.limitedParallelism(1)).  QuickJS is NOT thread-safe.
 * js_init_module_std / js_init_module_os are intentionally NOT registered.
 */
#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include "quickjs_ng/quickjs.h"

#define LOG_TAG "QuickJsEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ─── per-context state ─────────────────────────────────────────────────── */
typedef struct {
    JavaVM    *jvm;
    jobject    engineRef;
    jmethodID  resolveId;    /* resolveCallback(key:Long, value:String?) */
    jmethodID  rejectId;     /* rejectCallback(key:Long, msg:String)     */
    jmethodID  invokeHostId; /* invokeHostFunction(ns,name,args):String  */
} EngineState;

static EngineState *get_state(JSContext *ctx) {
    return (EngineState *)JS_GetContextOpaque(ctx);
}

/* ─── JNI attach helpers ────────────────────────────────────────────────── */
static JNIEnv *attach_jni(JavaVM *jvm, int *att) {
    JNIEnv *env = NULL; *att = 0;
    if ((*jvm)->GetEnv(jvm,(void**)&env,JNI_VERSION_1_6)!=JNI_OK){
        (*jvm)->AttachCurrentThread(jvm,&env,NULL); *att=1;
    }
    return env;
}
static void detach_if(JavaVM *jvm, int att){ if(att)(*jvm)->DetachCurrentThread(jvm); }

/* ─── exception helper ──────────────────────────────────────────────────── */
static char *exc_str(JSContext *ctx){
    JSValue e=JS_GetException(ctx);
    const char *m=JS_ToCString(ctx,e);
    char *r=m?strdup(m):strdup("unknown JS error");
    if(m) JS_FreeCString(ctx,m);
    JS_FreeValue(ctx,e); return r;
}

/* ─── globalThis.__resolveCallback(key, value) ─────────────────────────── */
static JSValue js_resolve(JSContext *ctx, JSValueConst this_val,
                           int argc, JSValueConst *argv, int magic) {
    if(argc<1) return JS_UNDEFINED;
    EngineState *s=get_state(ctx); if(!s) return JS_UNDEFINED;
    double kd=0; JS_ToFloat64(ctx,&kd,argv[0]);
    int att; JNIEnv *env=attach_jni(s->jvm,&att);
    jstring jv=NULL;
    if(argc>=2 && !JS_IsNull(argv[1]) && !JS_IsUndefined(argv[1])){
        const char *str=JS_ToCString(ctx,argv[1]);
        if(str){ jv=(*env)->NewStringUTF(env,str); JS_FreeCString(ctx,str); }
    }
    (*env)->CallVoidMethod(env,s->engineRef,s->resolveId,(jlong)kd,jv);
    if(jv)(*env)->DeleteLocalRef(env,jv);
    detach_if(s->jvm,att); return JS_UNDEFINED;
}

/* ─── globalThis.__rejectCallback(key, msg) ────────────────────────────── */
static JSValue js_reject(JSContext *ctx, JSValueConst this_val,
                          int argc, JSValueConst *argv, int magic) {
    if(argc<1) return JS_UNDEFINED;
    EngineState *s=get_state(ctx); if(!s) return JS_UNDEFINED;
    double kd=0; JS_ToFloat64(ctx,&kd,argv[0]);
    int att; JNIEnv *env=attach_jni(s->jvm,&att);
    const char *msg=(argc>=2)?JS_ToCString(ctx,argv[1]):NULL;
    jstring jm=(*env)->NewStringUTF(env,msg?msg:"Promise rejected");
    if(msg) JS_FreeCString(ctx,msg);
    (*env)->CallVoidMethod(env,s->engineRef,s->rejectId,(jlong)kd,jm);
    (*env)->DeleteLocalRef(env,jm);
    detach_if(s->jvm,att); return JS_UNDEFINED;
}

/* ─── globalThis.__hostDispatch(ns, name, argsJson) → Promise ──────────── */
static JSValue js_host_dispatch(JSContext *ctx, JSValueConst this_val,
                                 int argc, JSValueConst *argv, int magic) {
    if(argc<3) return JS_ThrowTypeError(ctx,"__hostDispatch: 3 args required");
    EngineState *s=get_state(ctx); if(!s) return JS_ThrowInternalError(ctx,"no state");
    const char *ns  =JS_ToCString(ctx,argv[0]);
    const char *name=JS_ToCString(ctx,argv[1]);
    const char *args=JS_ToCString(ctx,argv[2]);
    int att; JNIEnv *env=attach_jni(s->jvm,&att);
    jstring jns  =(*env)->NewStringUTF(env,ns  ?ns  :"");
    jstring jname=(*env)->NewStringUTF(env,name?name:"");
    jstring jargs=(*env)->NewStringUTF(env,args?args:"null");
    jstring jkey=(jstring)(*env)->CallObjectMethod(env,s->engineRef,s->invokeHostId,jns,jname,jargs);
    (*env)->DeleteLocalRef(env,jns);
    (*env)->DeleteLocalRef(env,jname);
    (*env)->DeleteLocalRef(env,jargs);
    detach_if(s->jvm,att);
    if(ns)   JS_FreeCString(ctx,ns);
    if(name) JS_FreeCString(ctx,name);
    if(args) JS_FreeCString(ctx,args);
    if(!jkey) return JS_ThrowInternalError(ctx,"invokeHostFunction returned null");
    att=0; env=attach_jni(s->jvm,&att);
    const char *kc=(*env)->GetStringUTFChars(env,jkey,NULL);
    char buf[512];
    snprintf(buf,sizeof(buf),
        "new Promise(function(res,rej){"
        "(globalThis.__hr=globalThis.__hr||{})[%s]={resolve:res,reject:rej};"
        "})", kc);
    (*env)->ReleaseStringUTFChars(env,jkey,kc);
    (*env)->DeleteLocalRef(env,jkey);
    detach_if(s->jvm,att);
    return JS_Eval(ctx,buf,strlen(buf),"<hp>",JS_EVAL_TYPE_GLOBAL);
}

/* ─── nativeCreateContext ───────────────────────────────────────────────── */
JNIEXPORT jlong JNICALL
Java_com_opentune_provider_js_QuickJsEngine_nativeCreateContext(JNIEnv *env, jobject thiz) {
    JSRuntime *rt=JS_NewRuntime(); if(!rt){LOGE("NewRuntime failed");return 0;}
    JS_SetMaxStackSize(rt, 512*1024);
    JS_SetMemoryLimit(rt, 32*1024*1024);
    JSContext *ctx=JS_NewContext(rt);
    if(!ctx){LOGE("NewContext failed");JS_FreeRuntime(rt);return 0;}
    EngineState *s=(EngineState*)calloc(1,sizeof(EngineState));
    if(!s){JS_FreeContext(ctx);JS_FreeRuntime(rt);return 0;}
    (*env)->GetJavaVM(env,&s->jvm);
    s->engineRef=(*env)->NewGlobalRef(env,thiz);
    jclass cls=(*env)->GetObjectClass(env,thiz);
    s->resolveId   =(*env)->GetMethodID(env,cls,"resolveCallback",   "(JLjava/lang/String;)V");
    s->rejectId    =(*env)->GetMethodID(env,cls,"rejectCallback",    "(JLjava/lang/String;)V");
    s->invokeHostId=(*env)->GetMethodID(env,cls,"invokeHostFunction",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    JS_SetContextOpaque(ctx,s);
    JSValue g=JS_GetGlobalObject(ctx);
    JS_SetPropertyStr(ctx,g,"__resolveCallback",
        JS_NewCFunctionMagic(ctx,js_resolve,"__resolveCallback",2,JS_CFUNC_generic_magic,0));
    JS_SetPropertyStr(ctx,g,"__rejectCallback",
        JS_NewCFunctionMagic(ctx,js_reject, "__rejectCallback", 2,JS_CFUNC_generic_magic,0));
    JS_SetPropertyStr(ctx,g,"__hostDispatch",
        JS_NewCFunctionMagic(ctx,js_host_dispatch,"__hostDispatch",3,JS_CFUNC_generic_magic,0));
    JS_FreeValue(ctx,g);
    return (jlong)(uintptr_t)ctx;
}

/* ─── nativeDestroyContext ──────────────────────────────────────────────── */
JNIEXPORT void JNICALL
Java_com_opentune_provider_js_QuickJsEngine_nativeDestroyContext(JNIEnv *env, jobject thiz, jlong p) {
    JSContext *ctx=(JSContext*)(uintptr_t)p; if(!ctx) return;
    EngineState *s=get_state(ctx); JSRuntime *rt=JS_GetRuntime(ctx);
    JS_FreeContext(ctx); JS_FreeRuntime(rt);
    if(s){if(s->engineRef)(*env)->DeleteGlobalRef(env,s->engineRef);free(s);}
}

/* ─── nativeEvalBundle ──────────────────────────────────────────────────── */
JNIEXPORT jstring JNICALL
Java_com_opentune_provider_js_QuickJsEngine_nativeEvalBundle(JNIEnv *env, jobject thiz, jlong p, jstring code) {
    JSContext *ctx=(JSContext*)(uintptr_t)p;
    if(!ctx) return (*env)->NewStringUTF(env,"null context");
    const char *c=(*env)->GetStringUTFChars(env,code,NULL);
    JSValue r=JS_Eval(ctx,c,strlen(c),"<bundle>",JS_EVAL_TYPE_GLOBAL|JS_EVAL_FLAG_STRICT);
    (*env)->ReleaseStringUTFChars(env,code,c);
    if(JS_IsException(r)){
        char *m=exc_str(ctx); jstring jm=(*env)->NewStringUTF(env,m); free(m);
        JS_FreeValue(ctx,r); return jm;
    }
    JS_FreeValue(ctx,r); return NULL;
}

/* ─── nativeEvalSnippet ─────────────────────────────────────────────────── */
JNIEXPORT jstring JNICALL
Java_com_opentune_provider_js_QuickJsEngine_nativeEvalSnippet(JNIEnv *env, jobject thiz, jlong p, jstring code) {
    JSContext *ctx=(JSContext*)(uintptr_t)p;
    if(!ctx) return (*env)->NewStringUTF(env,"null context");
    const char *c=(*env)->GetStringUTFChars(env,code,NULL);
    JSValue r=JS_Eval(ctx,c,strlen(c),"<snippet>",JS_EVAL_TYPE_GLOBAL);
    (*env)->ReleaseStringUTFChars(env,code,c);
    if(JS_IsException(r)){
        char *m=exc_str(ctx); jstring jm=(*env)->NewStringUTF(env,m); free(m);
        JS_FreeValue(ctx,r); return jm;
    }
    JS_FreeValue(ctx,r); return NULL;
}

/* ─── nativeEvalExpression ──────────────────────────────────────────────── */
/* Evaluates a JS expression and returns its JSON.stringify'd value.
   Returns NULL on null/undefined result. Returns error message on exception. */
JNIEXPORT jstring JNICALL
Java_com_opentune_provider_js_QuickJsEngine_nativeEvalExpression(JNIEnv *env, jobject thiz, jlong p, jstring code) {
    JSContext *ctx=(JSContext*)(uintptr_t)p;
    if(!ctx) return (*env)->NewStringUTF(env,"null context");
    const char *c=(*env)->GetStringUTFChars(env,code,NULL);
    JSValue r=JS_Eval(ctx,c,strlen(c),"<expr>",JS_EVAL_TYPE_GLOBAL);
    (*env)->ReleaseStringUTFChars(env,code,c);
    if(JS_IsException(r)){
        char *m=exc_str(ctx); jstring jm=(*env)->NewStringUTF(env,m); free(m);
        JS_FreeValue(ctx,r); return jm;
    }
    if(JS_IsNull(r)||JS_IsUndefined(r)){ JS_FreeValue(ctx,r); return NULL; }
    JSValue global=JS_GetGlobalObject(ctx);
    JSValue json_obj=JS_GetPropertyStr(ctx,global,"JSON");
    JSValue stringify=JS_GetPropertyStr(ctx,json_obj,"stringify");
    JSValue result=JS_Call(ctx,stringify,json_obj,1,&r);
    JS_FreeValue(ctx,r); JS_FreeValue(ctx,stringify); JS_FreeValue(ctx,json_obj); JS_FreeValue(ctx,global);
    if(JS_IsException(result)){
        char *m=exc_str(ctx); jstring jm=(*env)->NewStringUTF(env,m); free(m);
        JS_FreeValue(ctx,result); return jm;
    }
    size_t len; const char *s=JS_ToCStringLen(ctx,&len,result);
    jstring out=(*env)->NewStringUTF(env,s);
    JS_FreeCString(ctx,s); JS_FreeValue(ctx,result);
    return out;
}

/* ─── nativeExecutePendingJobs ──────────────────────────────────────────── */
JNIEXPORT jint JNICALL
Java_com_opentune_provider_js_QuickJsEngine_nativeExecutePendingJobs(JNIEnv *env, jobject thiz, jlong p, jint max) {
    JSContext *ctx=(JSContext*)(uintptr_t)p; if(!ctx) return 0;
    JSRuntime *rt=JS_GetRuntime(ctx);
    int total=0; JSContext *pctx=NULL; int r;
    while(total<max){r=JS_ExecutePendingJob(rt,&pctx);if(r<=0)break;total++;}
    if(r<0&&pctx){char *m=exc_str(pctx);LOGE("pending job exc: %s",m);free(m);}
    return total;
}

/* ─── nativeCallMethod ──────────────────────────────────────────────────── */
JNIEXPORT jstring JNICALL
Java_com_opentune_provider_js_QuickJsEngine_nativeCallMethod(
        JNIEnv *env, jobject thiz, jlong p,
        jstring methodName, jstring argsJson, jlong callbackKey) {
    JSContext *ctx=(JSContext*)(uintptr_t)p;
    if(!ctx) return (*env)->NewStringUTF(env,"null context");
    const char *meth=(*env)->GetStringUTFChars(env,methodName,NULL);
    const char *args=(*env)->GetStringUTFChars(env,argsJson,NULL);
    JSValue g=JS_GetGlobalObject(ctx);
    JSValue prov=JS_GetPropertyStr(ctx,g,"opentuneProvider");
    jstring err=NULL;
    if(JS_IsUndefined(prov)||JS_IsNull(prov)){
        err=(*env)->NewStringUTF(env,"opentuneProvider not on globalThis");goto done;
    }
    {
        JSValue fn=JS_GetPropertyStr(ctx,prov,meth);
        if(!JS_IsFunction(ctx,fn)){
            err=(*env)->NewStringUTF(env,"method not found");
            JS_FreeValue(ctx,fn); goto done;
        }
        JSValue arg=JS_ParseJSON(ctx,args,strlen(args),"<args>");
        if(JS_IsException(arg)){
            char *m=exc_str(ctx); err=(*env)->NewStringUTF(env,m); free(m);
            JS_FreeValue(ctx,fn); goto done;
        }
        JSValue promise=JS_Call(ctx,fn,prov,1,&arg);
        JS_FreeValue(ctx,arg); JS_FreeValue(ctx,fn);
        if(JS_IsException(promise)){
            char *m=exc_str(ctx); err=(*env)->NewStringUTF(env,m); free(m); goto done;
        }
        /* Store promise, eval JS to attach then/catch referencing key as float64 */
        JS_SetPropertyStr(ctx,g,"__pendingPromise",promise); /* steals ref */
        char attach[1024];
        snprintf(attach,sizeof(attach),
            "(function(){"
            "var p=globalThis.__pendingPromise;"
            "delete globalThis.__pendingPromise;"
            "p.then("
            "  function(v){globalThis.__resolveCallback(%.0f,"
            "    v==null||v===undefined?null:typeof v==='string'?v:JSON.stringify(v));}"
            " ,function(e){globalThis.__rejectCallback(%.0f,"
            "    e&&e.message?e.message:String(e));}"
            ");})();", (double)callbackKey, (double)callbackKey);
        JSValue ar=JS_Eval(ctx,attach,strlen(attach),"<attach>",JS_EVAL_TYPE_GLOBAL);
        if(JS_IsException(ar)){char *m=exc_str(ctx);LOGE("attach err: %s",m);free(m);}
        JS_FreeValue(ctx,ar);
    }
done:
    JS_FreeValue(ctx,prov); JS_FreeValue(ctx,g);
    (*env)->ReleaseStringUTFChars(env,methodName,meth);
    (*env)->ReleaseStringUTFChars(env,argsJson,args);
    return err;
}

/* ─── nativeResolveHostCall ─────────────────────────────────────────────── */
JNIEXPORT jstring JNICALL
Java_com_opentune_provider_js_QuickJsEngine_nativeResolveHostCall(
        JNIEnv *env, jobject thiz, jlong p, jlong key, jstring resultJson) {
    JSContext *ctx=(JSContext*)(uintptr_t)p; if(!ctx) return NULL;
    const char *json=resultJson?(*env)->GetStringUTFChars(env,resultJson,NULL):NULL;
    char buf[8192];
    snprintf(buf,sizeof(buf),
        "(function(){var r=globalThis.__hr&&globalThis.__hr[%.0f];"
        "if(r){r.resolve(%s);delete globalThis.__hr[%.0f];}})();",
        (double)key, json?json:"null", (double)key);
    if(json)(*env)->ReleaseStringUTFChars(env,resultJson,json);
    JSValue r=JS_Eval(ctx,buf,strlen(buf),"<resolve>",JS_EVAL_TYPE_GLOBAL);
    if(JS_IsException(r)){
        char *m=exc_str(ctx);jstring jm=(*env)->NewStringUTF(env,m);free(m);
        JS_FreeValue(ctx,r);return jm;
    }
    JS_FreeValue(ctx,r); return NULL;
}

/* ─── nativeRejectHostCall ──────────────────────────────────────────────── */
JNIEXPORT jstring JNICALL
Java_com_opentune_provider_js_QuickJsEngine_nativeRejectHostCall(
        JNIEnv *env, jobject thiz, jlong p, jlong key, jstring errorMsg) {
    JSContext *ctx=(JSContext*)(uintptr_t)p; if(!ctx) return NULL;
    const char *msg=errorMsg?(*env)->GetStringUTFChars(env,errorMsg,NULL):"host error";
    char esc[2048]; int j=0;
    for(int i=0;msg[i]&&j<2040;i++){
        if(msg[i]=='\\'||msg[i]=='"') esc[j++]='\\';
        esc[j++]=msg[i];
    }
    esc[j]='\0';
    char buf[4096];
    snprintf(buf,sizeof(buf),
        "(function(){var r=globalThis.__hr&&globalThis.__hr[%.0f];"
        "if(r){r.reject(new Error(\"%s\"));delete globalThis.__hr[%.0f];}})();",
        (double)key, esc, (double)key);
    if(errorMsg)(*env)->ReleaseStringUTFChars(env,errorMsg,msg);
    JSValue r=JS_Eval(ctx,buf,strlen(buf),"<reject>",JS_EVAL_TYPE_GLOBAL);
    if(JS_IsException(r)){
        char *m=exc_str(ctx);jstring jm=(*env)->NewStringUTF(env,m);free(m);
        JS_FreeValue(ctx,r);return jm;
    }
    JS_FreeValue(ctx,r); return NULL;
}
