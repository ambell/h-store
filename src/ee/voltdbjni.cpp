/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
 *
 * This file contains original code and/or modifications of original code.
 * Any modifications made by VoltDB L.L.C. are licensed under the following
 * terms and conditions:
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */
/* Copyright (C) 2008 by H-Store Project
 * Brown University
 * Massachusetts Institute of Technology
 * Yale University
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

/**
 * @defgroup jni The JNI entry points for local execution engine written in C++.
 * An execution engine object is VoltDBEngine* created by create() method.
 * Every JNI method requires the VoltDBEngine* as a parameter instead
 * of having any static (it's simpler and safer, isn't it?).
 * For more details, see the package Javadoc of org.voltdb.jni.
 * @{
*/

#include <string>
#include <vector>
#include <signal.h>
#include <dlfcn.h>
#ifdef LINUX
#include <sys/types.h>
#include <sys/sysinfo.h>
#include <sys/mman.h>
#include <unistd.h>
#ifndef __USE_GNU
#define  __USE_GNU
#endif
#include <sched.h>
#endif

//#include <google/profiler.h>

//#include <jni/jni.h>
// TODO: gcc picks up wrong jni_md.h and results in compile error (bad
// declaration of jboolean) if I include the jni.h in externals.
// Can we assume jni.h? or still better to have jni.h in externals?
#include <jni.h>

#include "org_voltdb_jni_ExecutionEngine.h" // the header file output by javah
#include "org_voltdb_utils_DBBPool.h" //Utility method for DBBContainer
#include "org_voltdb_utils_ThreadUtils.h"


#include "boost/shared_ptr.hpp"
#include "boost/scoped_array.hpp"
#include "common/debuglog.h"
#include "common/serializeio.h"
#include "common/TheHashinator.h"
#include "common/Pool.hpp"
#include "common/FatalException.hpp"
#include "execution/VoltDBEngine.h"
#include "execution/JNITopend.h"
#include "json_spirit/json_spirit.h"
#include "boost/pool/pool.hpp"
#include "boost/crc.hpp"
#include "logging/JNILogProxy.h"

#include "logging/LogDefs.h"
#include "logging/Logger.h"

// Defines a function as a JNIEXPORT for a shared library
#define SHAREDLIB_JNIEXPORT __attribute__((visibility("default"))) JNIEXPORT

using namespace std;
using namespace voltdb;

#define castToEngine(x) reinterpret_cast<VoltDBEngine*>((x));\
    currentEngine = reinterpret_cast<VoltDBEngine*>((x))
#define updateJNILogProxy(x) const_cast<JNILogProxy*>(dynamic_cast<const JNILogProxy*>(x->getLogManager()->getLogProxy()))->setJNIEnv(env)

/*
 * Yes, I know. This is ugly. But in order to get some useful information and
 * properly call crashVoltDB when a signal is caught, I cannot think of a
 * different way.
 */
static VoltDBEngine *currentEngine = NULL;
static JavaVM *currentVM = NULL;

/**
 * The following code is for handling signals. A stack trace will be printed
 * when a SIGSEGV is caught.
 *
 * The code is modified based on the original code found at
 * http://tlug.up.ac.za/wiki/index.php/Obtaining_a_stack_trace_in_C_upon_SIGSEGV
 *
 * This source file is used to print out a stack-trace when your program
 * segfaults. It is relatively reliable and spot-on accurate.
 *
 * This code is in the public domain. Use it as you see fit, some credit
 * would be appreciated, but is not a prerequisite for usage. Feedback
 * on it's use would encourage further development and maintenance.
 *
 * Due to a bug in gcc-4.x.x you currently have to compile as C++ if you want
 * demangling to work.
 *
 * Please note that it's been ported into my ULS library, thus the check for
 * HAS_ULSLIB and the use of the sigsegv_outp macro based on that define.
 *
 * Author: Jaco Kroon <jaco@kroon.co.za>
 *
 * Copyright (C) 2005 - 2010 Jaco Kroon
 */
#if defined(REG_RIP)
# define SIGSEGV_STACK_IA64
#elif defined(REG_EIP)
# define SIGSEGV_STACK_X86
#else
# define SIGSEGV_STACK_GENERIC
#endif

void signalHandler(int signum, siginfo_t *info, void *context) {
    if (currentVM == NULL || currentEngine == NULL)
        return;

    char err_msg[128];
    snprintf(err_msg, 128, "SIGSEGV caught: signal number %d, error value %d,"
             " signal code %d\n\n", info->si_signo, info->si_errno,
             info->si_code);
    std::string message = err_msg;
    message.append(currentEngine->debug());
    FatalException e = FatalException(message.c_str(), __FILE__, __LINE__);

#ifndef SIGSEGV_NOSTACK
#if defined(SIGSEGV_STACK_IA64) || defined(SIGSEGV_STACK_X86)

    ucontext_t *ucontext = (ucontext_t *)context;
    int f = 0;
    Dl_info dlinfo;
    void **bp = 0;
    void *ip = 0;
    std::vector<std::string> traces;

#if defined(SIGSEGV_STACK_IA64)
    ip = (void*)ucontext->uc_mcontext.gregs[REG_RIP];
    bp = (void**)ucontext->uc_mcontext.gregs[REG_RBP];
#elif defined(SIGSEGV_STACK_X86)
    ip = (void*)ucontext->uc_mcontext.gregs[REG_EIP];
    bp = (void**)ucontext->uc_mcontext.gregs[REG_EBP];
#endif

    if (!bp || !ip) {
        e.m_traces = traces;
    }

    while(bp && ip) {
        if(!dladdr(ip, &dlinfo))
            break;

        const char *symname = dlinfo.dli_sname;
        char trace[1024];

#ifndef NO_CPP_DEMANGLE
        int status;
        char * tmp = abi::__cxa_demangle(symname, NULL, 0, &status);

        if (status == 0 && tmp)
            symname = tmp;
#endif

        sprintf(trace, "% 2d: %p <%s+%lu> (%s)\n",
                ++f,
                ip,
                symname,
                (unsigned long)ip - (unsigned long)dlinfo.dli_saddr,
                dlinfo.dli_fname);
        traces.push_back(std::string(trace));

#ifndef NO_CPP_DEMANGLE
        if (tmp)
            free(tmp);
#endif

        if(dlinfo.dli_sname && !strcmp(dlinfo.dli_sname, "main"))
            break;

        ip = bp[1];
        bp = (void**)bp[0];
    }
#endif
#endif

    JNIEnv *env;
    if (currentVM->AttachCurrentThread((void **)(void *)&env, NULL) != 0)
        exit(-1);
    Topend *topend = static_cast<JNITopend*>(currentEngine->getTopend())->updateJNIEnv(env);
    topend->crashVoltDB(e);
    currentVM->DetachCurrentThread();
}

void setupSigHandler(void) {
#ifdef __linux__
    struct sigaction action;
    struct sigaction orig_action;
    memset(&action, 0, sizeof(action));
    action.sa_sigaction = signalHandler;
    action.sa_flags = SA_SIGINFO;
    if(sigaction(SIGSEGV, &action, &orig_action) < 0)
        return;
    /*
     * This detects if we are running in Sun's JVM and libjsig.so is loaded. The
     * trick is that the interposed version of sigaction() returns the same
     * signal handler as the one we passed in, the original version of
     * sigaction() returns the old signal handler in place before we put in
     * ours. So here we check if the returned one is ours.
     */
    if (orig_action.sa_sigaction != NULL
        && orig_action.sa_sigaction != signalHandler)
        sigaction(SIGSEGV, &orig_action, NULL);
#endif
}

////////////////////////////////////////////////////////////////////////////
// Create / Destroy
////////////////////////////////////////////////////////////////////////////
/**
 * Just creates a new VoltDBEngine object and retunrs it to Java.
 * Never fail to destroy() for the VoltDBEngine* once you call this method
 * NOTE: Call initialize() separately for initialization.
 * This does strictly nothing so that this method never throws an exception.
 * @return the created VoltDBEngine pointer casted to jlong.
*/
SHAREDLIB_JNIEXPORT jlong JNICALL Java_org_voltdb_jni_ExecutionEngine_nativeCreate(JNIEnv *env, jobject obj, jboolean isSunJVM) {
    // obj is the instance pointer of the ExecutionEngineJNI instance
    // that is creating this native EE. Turn this into a global reference
    // and only use that global reference for calling back to Java.
    // The jobject parameter here (and in all the other native interfaces)
    // is a local reference and only valid until the return of the
    // native invocation. Since calling patterns like java->ee->java->ee-
    // exist, the local jobject pointers are basically uncacheable. The
    // second java->ee call may generate a new local reference that would
    // be invalid in the previous stack frames (after the return of the
    // last ee native call.)

    jobject java_ee = env->NewGlobalRef(obj);
    if (java_ee == NULL) {
        assert(!"Failed to allocate global reference to java EE.");
        throw std::exception();
        return 0;
    }
    JavaVM *vm;
    env->GetJavaVM(&vm);
    currentVM = vm;
    if (isSunJVM == JNI_TRUE)
        setupSigHandler();
    JNITopend *topend = NULL;
    VoltDBEngine *engine = NULL;
    try {
        topend = new JNITopend(env, java_ee);
        engine = new VoltDBEngine( topend, JNILogProxy::getJNILogProxy(env, vm));
    } catch (FatalException e) {
        if (topend != NULL) {
            topend->crashVoltDB(e);
        }
    }
    return reinterpret_cast<jlong>(engine);
}
/**
 * Releases all resources held in the execution engine.
 * @param engine_ptr the VoltDBEngine pointer to be destroyed
 * @return error code
*/
SHAREDLIB_JNIEXPORT jint JNICALL Java_org_voltdb_jni_ExecutionEngine_nativeDestroy(
    JNIEnv *env, jobject obj,
    jlong engine_ptr) {
    VoltDBEngine *engine = castToEngine(engine_ptr);
    static_cast<JNITopend*>(engine->getTopend())->updateJNIEnv(env);
    updateJNILogProxy(engine); //JNIEnv pointer can change between calls, must be updated
    if (engine == NULL) {
        return org_voltdb_jni_ExecutionEngine_ERRORCODE_ERROR;
    }
    delete engine;
    return org_voltdb_jni_ExecutionEngine_ERRORCODE_SUCCESS;
}

////////////////////////////////////////////////////////////////////////////
// Initialization
////////////////////////////////////////////////////////////////////////////
/**
 * Initializes the execution engine with given parameter.
 * @param enginePtr the VoltDBEngine pointer to be initialized
 * @param clusterId id of the cluster the execution engine belongs to
 * @param nodeId this id will be set to the execution engine
 * @return error code
*/
SHAREDLIB_JNIEXPORT jint JNICALL Java_org_voltdb_jni_ExecutionEngine_nativeInitialize(
    JNIEnv *env, jobject obj,
    jlong enginePtr,
    jint clusterIndex,
    jint siteId,
    jint partitionId,
    jint hostId,
    jstring hostname) {
    VOLT_DEBUG("nativeInitialize() start");
    VoltDBEngine *engine = castToEngine(enginePtr);
    Topend *topend = static_cast<JNITopend*>(engine->getTopend())->updateJNIEnv(env);
    if (engine == NULL) {
        VOLT_ERROR("engine_ptr was NULL or invalid pointer");
        return org_voltdb_jni_ExecutionEngine_ERRORCODE_ERROR;
    }
    try {
        updateJNILogProxy(engine); //JNIEnv pointer can change between calls, must be updated

        const char *hostChars = env->GetStringUTFChars( hostname, NULL);
        std::string hostString(hostChars);
        env->ReleaseStringUTFChars( hostname, hostChars);
        // initialization is separated from constructor so that constructor
        // never fails.
        VOLT_DEBUG("calling initialize...");
        bool success =
                engine->initialize(
                        clusterIndex,
                        siteId,
                        partitionId,
                        hostId,
                        hostString);

        if (success) {
            VOLT_DEBUG("initialize succeeded");
            return org_voltdb_jni_ExecutionEngine_ERRORCODE_SUCCESS;
        } else {
            throwFatalException("initialize failed");
            return org_voltdb_jni_ExecutionEngine_ERRORCODE_ERROR;
        }
    } catch (FatalException e) {
        topend->crashVoltDB(e);
    }
    return org_voltdb_jni_ExecutionEngine_ERRORCODE_ERROR;
}

/**
 * Load the system catalog for this engine.
 * @param engine_ptr the VoltDBEngine pointer
 * @param serialized_catalog the root catalog object serialized as text strings.
 * this parameter is jstring, not jbytearray because Catalog is serialized into
 * human-readable text strings separated by line feeds.
 * @return error code
*/
SHAREDLIB_JNIEXPORT jint JNICALL Java_org_voltdb_jni_ExecutionEngine_nativeLoadCatalog(
    JNIEnv *env, jobject obj,
    jlong engine_ptr, jstring serialized_catalog) {
    VOLT_DEBUG("nativeLoadCatalog() start");
    VoltDBEngine *engine = castToEngine(engine_ptr);
    static_cast<JNITopend*>(engine->getTopend())->updateJNIEnv(env);
    if (engine == NULL) {
        VOLT_ERROR("engine_ptr was NULL or invalid pointer");
        return org_voltdb_jni_ExecutionEngine_ERRORCODE_ERROR;
    }

    //JNIEnv pointer can change between calls, must be updated
    updateJNILogProxy(engine);

    //copy to std::string. utf_chars may or may not by a copy of the string
    const char* utf_chars = env->GetStringUTFChars(serialized_catalog, NULL);
    string str(utf_chars);
    env->ReleaseStringUTFChars(serialized_catalog, utf_chars);
    VOLT_DEBUG("calling loadCatalog...");

    try {
        bool success = engine->loadCatalog(str);

        if (success) {
            VOLT_DEBUG("loadCatalog succeeded");
            return org_voltdb_jni_ExecutionEngine_ERRORCODE_SUCCESS;
        }
    } catch (SerializableEEException &e) {
        engine->resetReusedResultOutputBuffer();
        e.serialize(engine->getExceptionOutputSerializer());
    }

    VOLT_ERROR("loadCatalog failed");
    return org_voltdb_jni_ExecutionEngine_ERRORCODE_ERROR;
}

/**
 * Load the system catalog for this engine.
 * @param engine_ptr the VoltDBEngine pointer
 * @param serialized_catalog the root catalog object serialized as text strings.
 * this parameter is jstring, not jbytearray because Catalog is serialized into
 * human-readable text strings separated by line feeds.
 * @return error code
*/
SHAREDLIB_JNIEXPORT jint JNICALL
Java_org_voltdb_jni_ExecutionEngine_nativeUpdateCatalog(
    JNIEnv *env, jobject obj,
    jlong engine_ptr, jstring catalog_diffs) {
    VOLT_DEBUG("nativeUpdateCatalog() start");
    VoltDBEngine *engine = castToEngine(engine_ptr);
    static_cast<JNITopend*>(engine->getTopend())->updateJNIEnv(env);
    if (engine == NULL) {
        VOLT_ERROR("engine_ptr was NULL or invalid pointer");
        return org_voltdb_jni_ExecutionEngine_ERRORCODE_ERROR;
    }

    //JNIEnv pointer can change between calls, must be updated
    updateJNILogProxy(engine);

    //copy to std::string. utf_chars may or may not by a copy of the string
    const char* utf_chars = env->GetStringUTFChars(catalog_diffs, NULL);
    string str(utf_chars);
    env->ReleaseStringUTFChars(catalog_diffs, utf_chars);
    VOLT_DEBUG("calling loadCatalog...");

    try {
        bool success = engine->updateCatalog(str);

        if (success) {
            VOLT_DEBUG("updateCatalog succeeded");
            return org_voltdb_jni_ExecutionEngine_ERRORCODE_SUCCESS;
        }
    } catch (SerializableEEException &e) {
        engine->resetReusedResultOutputBuffer();
        e.serialize(engine->getExceptionOutputSerializer());
    }

    VOLT_ERROR("updateCatalog failed");
    return org_voltdb_jni_ExecutionEngine_ERRORCODE_ERROR;
}

/**
 * This method is called to initially load table data.
 * @param pointer the VoltDBEngine pointer
 * @param table_id catalog ID of the table
 * @param serialized_table the table data to be loaded
*/
SHAREDLIB_JNIEXPORT jint JNICALL
Java_org_voltdb_jni_ExecutionEngine_nativeLoadTable (
    JNIEnv *env, jobject obj, jlong engine_ptr, jint table_id,
    jbyteArray serialized_table, jlong txnId, jlong lastCommittedTxnId,
    jlong undoToken, jboolean allowELT)
{
    VoltDBEngine *engine = castToEngine(engine_ptr);
    Topend *topend = static_cast<JNITopend*>(engine->getTopend())->updateJNIEnv(env);
    if (engine == NULL) {
        return org_voltdb_jni_ExecutionEngine_ERRORCODE_ERROR;
    }

    //JNIEnv pointer can change between calls, must be updated
    updateJNILogProxy(engine);
    engine->setUndoToken(undoToken);
    VOLT_DEBUG("loading table %d in C++...", table_id);

    // convert jboolean to bool
    bool bAllowELT = (allowELT == JNI_FALSE ? false : true);

    // deserialize dependency.
    jsize length = env->GetArrayLength(serialized_table);
    VOLT_DEBUG("deserializing %d bytes ...", (int) length);
    jbyte *bytes = env->GetByteArrayElements(serialized_table, NULL);
    ReferenceSerializeInput serialize_in(bytes, length);
    try {
        try {
            bool success = engine->loadTable(bAllowELT, table_id, serialize_in,
                                             txnId, lastCommittedTxnId);
            env->ReleaseByteArrayElements(serialized_table, bytes, JNI_ABORT);
            VOLT_DEBUG("deserialized table");

            if (success)
                return org_voltdb_jni_ExecutionEngine_ERRORCODE_SUCCESS;
        } catch (SerializableEEException &e) {
            engine->resetReusedResultOutputBuffer();
            e.serialize(engine->getExceptionOutputSerializer());
        }
    } catch (FatalException e) {
        topend->crashVoltDB(e);
    }

    return org_voltdb_jni_ExecutionEngine_ERRORCODE_ERROR;
}


////////////////////////////////////////////////////////////////////////////
// PlanNode Execution
////////////////////////////////////////////////////////////////////////////
/**
 * Utility used for deserializing ParameterSet passed from Java.
 */
void deserializeParameterSetCommon(int cnt, ReferenceSerializeInput &serialize_in,
                                   NValueArray &params, Pool *stringPool)
{
    for (int i = 0; i < cnt; ++i) {
        params[i] = NValue::deserializeFromAllocateForStorage(serialize_in, stringPool);
    }
}

/**
 * Utility used for deserializing ParameterSet passed from Java.
 */
int deserializeParameterSet(const char* serialized_parameterset, jint serialized_length,
    NValueArray &params, Pool *stringPool) {
    // deserialize parameters as ValueArray.
    // We don't use SerializeIO here because it makes a copy.
    ReferenceSerializeInput serialize_in(serialized_parameterset, serialized_length);

    // see org.voltdb.ParameterSet.
    // TODO : make it a class. later, later, later...
    int cnt = serialize_in.readShort();
    if (cnt < 0) {
        throwFatalException( "parameter count is negative: %d", cnt);
    }
    assert (cnt < MAX_PARAM_COUNT);
    deserializeParameterSetCommon(cnt, serialize_in, params, stringPool);
    return cnt;
}

/**
 * Sets (or re-sets) the buffer shared between java and the EE. This is for reducing
 * cost of GetDirectBufferAddress().
 * @param pointer the VoltDBEngine pointer
 * @param parameter_buffer direct byte buffer to be set
 * @param m_parameterBuffersize size of the buffer
 * @param result_buffer direct byte buffer to be set
 * @param result_buffer_size size of the buffer
 * @param exception_buffer direct byte buffer to be set
 * @param exception_buffer_size size of the buffer
 * @return error code
*/
SHAREDLIB_JNIEXPORT jint JNICALL Java_org_voltdb_jni_ExecutionEngine_nativeSetBuffers
  (JNIEnv *env, jobject obj, jlong engine_ptr, jobject parameter_buffer, jint parameter_buffer_size,
   jobject result_buffer, jint result_buffer_size,
   jobject exception_buffer, jint exception_buffer_size)
{
    VOLT_DEBUG("nativeSetBuffers() start");
    VoltDBEngine *engine = castToEngine(engine_ptr);
    Topend *topend = static_cast<JNITopend*>(engine->getTopend())->updateJNIEnv(env);
    if (engine == NULL) {
        return org_voltdb_jni_ExecutionEngine_ERRORCODE_ERROR;
    }
    try {
        updateJNILogProxy(engine); //JNIEnv pointer can change between calls, must be updated

        char *parameterBuffer = reinterpret_cast<char*>(
                env->GetDirectBufferAddress(parameter_buffer));
        int parameterBufferCapacity = parameter_buffer_size;

        char *reusedResultBuffer = reinterpret_cast<char*>(
                env->GetDirectBufferAddress(result_buffer));
        int reusedResultBufferCapacity = result_buffer_size;

        char *exceptionBuffer = reinterpret_cast<char*>(
                 env->GetDirectBufferAddress(exception_buffer));
        int exceptionBufferCapacity = exception_buffer_size;

        engine->setBuffers(parameterBuffer, parameterBufferCapacity,
            reusedResultBuffer, reusedResultBufferCapacity,
            exceptionBuffer, exceptionBufferCapacity);
    } catch (FatalException e) {
        topend->crashVoltDB(e);
    }

    return org_voltdb_jni_ExecutionEngine_ERRORCODE_SUCCESS;
}

/**
 * Executes a plan fragment with the given parameter set.
 * @param engine_ptr the VoltDBEngine pointer
 * @param plan_fragment_id ID of the plan fragment to be executed.
 * @return error code
*/
SHAREDLIB_JNIEXPORT jint JNICALL Java_org_voltdb_jni_ExecutionEngine_nativeExecutePlanFragment (
        JNIEnv *env,
        jobject obj,
        jlong engine_ptr,
        jlong plan_fragment_id,
        jint outputDependencyId,
        jint inputDependencyId,
        jlong txnId,
        jlong lastCommittedTxnId,
        jlong undoToken) {
    VOLT_DEBUG("nativeExecutePlanFragment() start");
    VoltDBEngine *engine = castToEngine(engine_ptr);
    Topend *topend = static_cast<JNITopend*>(engine->getTopend())->updateJNIEnv(env);
    assert(engine);
    try {
        updateJNILogProxy(engine); //JNIEnv pointer can change between calls, must be updated
        engine->setUndoToken(undoToken);
        engine->resetReusedResultOutputBuffer();
        NValueArray &params = engine->getParameterContainer();
        Pool *stringPool = engine->getStringPool();
        const int paramcnt = deserializeParameterSet(engine->getParameterBuffer(), engine->getParameterBufferCapacity(), params, engine->getStringPool());
        engine->setUsedParamcnt(paramcnt);
        const int retval = engine->executeQuery(plan_fragment_id, outputDependencyId, inputDependencyId, params, txnId, lastCommittedTxnId, true, true);
        stringPool->purge();
        return retval;
    } catch (FatalException e) {
        topend->crashVoltDB(e);
    }
    return org_voltdb_jni_ExecutionEngine_ERRORCODE_ERROR;
}

/*
 * Executes a plan fragment of an adhoc query.
 * Class:     org_voltdb_jni_ExecutionEngine
 * Method:    nativeExecuteCustomPlanFragment
 * Signature: (JLjava/lang/String;JJJ)I
 */
SHAREDLIB_JNIEXPORT jint JNICALL
Java_org_voltdb_jni_ExecutionEngine_nativeExecuteCustomPlanFragment (
    JNIEnv *env,
    jobject obj,
    jlong engine_ptr,
    jstring plan,
    jint outputDependencyId,
    jint inputDependencyId,
    jlong txnId,
    jlong lastCommittedTxnId,
    jlong undoToken) {
    int retval = org_voltdb_jni_ExecutionEngine_ERRORCODE_ERROR;

    VOLT_DEBUG("nativeExecuteCustomPlanFragment() start");

    // setup
    VoltDBEngine *engine = castToEngine(engine_ptr);
    assert(engine);
    static_cast<JNITopend*>(engine->getTopend())->updateJNIEnv(env);

    //JNIEnv pointer can change between calls, must be updated
    updateJNILogProxy(engine);
    engine->resetReusedResultOutputBuffer();
    engine->setUndoToken(undoToken);
    static_cast<JNITopend*>(engine->getTopend())->updateJNIEnv(env);
    Pool *stringPool = engine->getStringPool();

    // convert java plan string to stdc++ string plan
    const char *str = static_cast<const char*>(env->GetStringUTFChars(plan,
                                                                      NULL));
    assert(str);
    string cppplan = str;
    env->ReleaseStringUTFChars(plan, str);

    // execute
    engine->setUsedParamcnt(0);
    retval = engine->executePlanFragment(cppplan, outputDependencyId,
                                         inputDependencyId, txnId,
                                         lastCommittedTxnId);

    // cleanup
    stringPool->purge();

    return retval;
}

/**
 * Executes multiple plan fragments with the given parameter sets and gets the results.
 * @param pointer the VoltDBEngine pointer
 * @param plan_fragment_ids ID of the plan fragment to be executed.
 * @param outputBuffer buffer to be filled with the tables.
 * @param outputCapacity maximum number of bytes to write to buffer.
 * @return error code
*/
SHAREDLIB_JNIEXPORT jint JNICALL Java_org_voltdb_jni_ExecutionEngine_nativeExecuteQueryPlanFragmentsAndGetResults
(JNIEnv *env,
        jobject obj,
        jlong engine_ptr,
        jlongArray plan_fragment_ids,
        jint num_fragments,
        jintArray input_depIds,
        jintArray output_depIds,
        jlong txnId,
        jlong lastCommittedTxnId,
        jlong undoToken) {
    //VOLT_DEBUG("nativeExecuteQueryPlanFragmentAndGetResults() start");

    // setup
    VoltDBEngine *engine = castToEngine(engine_ptr);
    assert(engine);
    Topend *topend = static_cast<JNITopend*>(engine->getTopend())->updateJNIEnv(env);
    try {
        updateJNILogProxy(engine); //JNIEnv pointer can change between calls, must be updated
        engine->resetReusedResultOutputBuffer();
        engine->setUndoToken(undoToken);
        static_cast<JNITopend*>(engine->getTopend())->updateJNIEnv(env);
        Pool *stringPool = engine->getStringPool();

        // fragment info
        int batch_size = num_fragments;
        assert (batch_size <= MAX_BATCH_COUNT);
        jlong* fragment_ids_buffer = engine->getBatchFragmentIdsContainer();
        env->GetLongArrayRegion(plan_fragment_ids, 0, batch_size, fragment_ids_buffer);

        /** PAVLO **/
        // input dep ids
        jint* input_depIds_buffer = engine->getBatchInputDepIdsContainer();
        env->GetIntArrayRegion(input_depIds, 0, batch_size, input_depIds_buffer);
        
        // output dep ids
        jint* output_depIds_buffer = engine->getBatchOutputDepIdsContainer();
        env->GetIntArrayRegion(output_depIds, 0, batch_size, output_depIds_buffer);
        /** PAVLO **/

        // all fragments' parameters are in this buffer
        ReferenceSerializeInput serialize_in(engine->getParameterBuffer(), engine->getParameterBufferCapacity());
        NValueArray &params = engine->getParameterContainer();

        // count failures
        int failures = 0;

        for (int i = 0; i < batch_size; ++i) {
            int cnt = serialize_in.readShort();
            if (cnt < 0) {
                throwFatalException("parameter count is negative: %d", cnt);
            }
            assert (cnt < MAX_PARAM_COUNT);
            deserializeParameterSetCommon(cnt, serialize_in, params, stringPool);

            engine->setUsedParamcnt(cnt);
            // success is 0 and error is 1.
            if (engine->executeQuery(fragment_ids_buffer[i],
                                     output_depIds_buffer[i],
                                     input_depIds_buffer[i],
                                     params, txnId, lastCommittedTxnId, i == 0,
                                     i == (batch_size - 1)))
            {
                ++failures;
            }
        }

        // cleanup
        stringPool->purge();

        if (failures > 0)
            return org_voltdb_jni_ExecutionEngine_ERRORCODE_ERROR;
        else
            return org_voltdb_jni_ExecutionEngine_ERRORCODE_SUCCESS;
    } catch (FatalException e) {
        topend->crashVoltDB(e);
    }
    return org_voltdb_jni_ExecutionEngine_ERRORCODE_ERROR;
}

/**
 * Serialize the result temporary table.
 * @param engine_ptr the VoltDBEngine pointer
 * @param table_id Id of the table to be serialized
 * @return serialized temporary table
*/
SHAREDLIB_JNIEXPORT jint JNICALL Java_org_voltdb_jni_ExecutionEngine_nativeSerializeTable(
        JNIEnv *env,
        jobject obj,
        jlong engine_ptr,
        jint table_id,
        jint offset,
        jint limit,
        jobject output_buffer,
        jint output_capacity) {
    //VOLT_DEBUG("nativeSerializeTable() start");
    VoltDBEngine *engine = castToEngine(engine_ptr);
    if (engine == NULL) {
        VOLT_ERROR("The VoltDBEngine pointer is null!");
        return org_voltdb_jni_ExecutionEngine_ERRORCODE_ERROR;
    }
    Topend *topend = static_cast<JNITopend*>(engine->getTopend())->updateJNIEnv(env);
    try {
        updateJNILogProxy(engine); //JNIEnv pointer can change between calls, must be updated
        void* data = env->GetDirectBufferAddress(output_buffer);
        ReferenceSerializeOutput out(data, output_capacity);

        bool success = engine->serializeTable(table_id, offset, limit, &out);

        if (!success) return org_voltdb_jni_ExecutionEngine_ERRORCODE_ERROR;
        else return org_voltdb_jni_ExecutionEngine_ERRORCODE_SUCCESS;
    } catch (FatalException e) {
        topend->crashVoltDB(e);
    }
    return org_voltdb_jni_ExecutionEngine_ERRORCODE_ERROR;
}

/*
 * Class:     org_voltdb_utils_DBBPool
 * Method:    getBufferAddress
 * Signature: (Ljava/nio/ByteBuffer;)J
 *
 * Returns the native address of the provided DirectByteBuffer as a long
 * @param env Pointer to the JNIEnv for this thread
 * @param obj Pointer to the object on which this method was called
 * @param buffer DirectByteBuffer
 * @return Native address of the DirectByteBuffer as a long
 */
SHAREDLIB_JNIEXPORT jlong JNICALL Java_org_voltdb_utils_DBBPool_getBufferAddress
  (JNIEnv *env, jclass clazz, jobject buffer)
{
    void *address = env->GetDirectBufferAddress(buffer);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        return (jlong)address;
    }
    assert(address);
    return reinterpret_cast<jlong>(address);
}

/*
 * Class:     org_voltdb_utils_DBBPool
 * Method:    getBufferCRC32
 * Signature: (Ljava/nio/ByteBuffer;II)I
 */
SHAREDLIB_JNIEXPORT jint JNICALL Java_org_voltdb_utils_DBBPool_getBufferCRC32
  (JNIEnv *env, jclass clazz, jobject buffer, jint offset, jint length) {
    char *address = reinterpret_cast<char*>(env->GetDirectBufferAddress(buffer));
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        return -1;
    }
    assert(address);
    boost::crc_32_type crc;
    crc.process_bytes(address + offset, length);
    return static_cast<jint>(crc.checksum());
}

/*
 * Class:     org_voltdb_jni_ExecutionEngine
 * Method:    nativeTick
 * Signature: (JJJ)V
 *
 * Called roughly every 1 second by the Java Runtime to allow the EE to do
 * periodic non-transactional work.
 *
 * @param env Pointer to the JNIEnv for this thread
 * @param obj Pointer to the object on which this method was called
 * @param engine_ptr Pointer to a VoltDBEngine instance
 * @param timeInMillis The current java timestamp (System.currentTimeMillis());
 * @param lastCommittedTxnId The id of the last committed transaction.
 */
SHAREDLIB_JNIEXPORT void JNICALL Java_org_voltdb_jni_ExecutionEngine_nativeTick
  (JNIEnv *env, jobject obj, jlong engine_ptr, jlong timeInMillis, jlong lastCommittedTxnId) {
    VoltDBEngine *engine = castToEngine(engine_ptr);
    Topend *topend = static_cast<JNITopend*>(engine->getTopend())->updateJNIEnv(env);
    try {
        updateJNILogProxy(engine); //JNIEnv pointer can change between calls, must be updated
        engine->tick(timeInMillis, lastCommittedTxnId);
    } catch (FatalException e) {
        topend->crashVoltDB(e);
    }
}

/*
 * Class:     org_voltdb_jni_ExecutionEngine
 * Method:    nativeQuiesce
 * Signature: (JJ)V
 *
 * Called to instruct the EE to reach an idle steady state.
 */
SHAREDLIB_JNIEXPORT void JNICALL Java_org_voltdb_jni_ExecutionEngine_nativeQuiesce
  (JNIEnv *env, jobject obj, jlong engine_ptr, jlong lastCommittedTxnId)
{
    VoltDBEngine *engine = castToEngine(engine_ptr);
    Topend *topend = static_cast<JNITopend*>(engine->getTopend())->updateJNIEnv(env);
    try {
        // JNIEnv pointer can change between calls, must be updated
        updateJNILogProxy(engine);
        engine->quiesce(lastCommittedTxnId);
    } catch (FatalException e) {
        topend->crashVoltDB(e);
    }
}

/**
 * Class:     org_voltdb_jni_ExecutionEngine
 * Method:    nativeGetStats
 * Signature: (I[I)Z
 *
 * Called to retrieve statistics
 *
 * @param env Pointer to the JNIEnv for this thread
 * @param obj Pointer to the object on which this method was called
 * @param engine_ptr Pointer to a VoltDBEngine instance
 * @param selector Ordinal value from StatisticsSelectorType enum indicating the type of stats to retrieve
 * @param locatorsArray Java array of CatalogIds indicating what set of sources should the statistics be retrieved from.
 * @return Number of result tables, 0 on no results, -1 on failure.
 */
SHAREDLIB_JNIEXPORT jint JNICALL
Java_org_voltdb_jni_ExecutionEngine_nativeGetStats(JNIEnv *env, jobject obj,
                                                   jlong pointer, jint selector,
                                                   jintArray locatorsArray,
                                                   jboolean jinterval,
                                                   jlong now) {
    VoltDBEngine *engine = castToEngine(pointer);
    Topend *topend = static_cast<JNITopend*>(engine->getTopend())->updateJNIEnv(env);
    /*
     * Can't use the standard JNI EE error code here because this method
     * actually uses that integer to indicate the number of result tables.
     */
    int result = -1;

    //JNIEnv pointer can change between calls, must be updated
    updateJNILogProxy(engine);
    engine->resetReusedResultOutputBuffer();

    /*
     * Retrieve locators if any
     */
    int *locators = NULL;
    int numLocators = 0;
    if (locatorsArray != NULL) {
        locators = env->GetIntArrayElements(locatorsArray, NULL);
        if (locators == NULL) {
            env->ExceptionDescribe();
            return JNI_FALSE;
        }
        numLocators = env->GetArrayLength(locatorsArray);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ReleaseIntArrayElements(locatorsArray, locators, JNI_ABORT);
            return JNI_FALSE;
        }
    }
    const bool interval = jinterval == JNI_TRUE ? true : false;

    try {
        result = engine->getStats(static_cast<int>(selector), locators,
                                  numLocators, interval, now);
    } catch (FatalException e) {
        topend->crashVoltDB(e);
    }

    env->ReleaseIntArrayElements(locatorsArray, locators, JNI_ABORT);

    return static_cast<jint>(result);
}

/*
 * Class:     org_voltdb_jni_ExecutionEngine
 * Method:    hashinate
 * Signature: (J)I
 */
SHAREDLIB_JNIEXPORT jint JNICALL Java_org_voltdb_jni_ExecutionEngine_hashinate__JI
  (JNIEnv *, jobject, jlong value, jint partitionCount) {
    return voltdb::TheHashinator::hashinate(value, partitionCount);
}

/*
 * Class:     org_voltdb_jni_ExecutionEngine
 * Method:    hashinate
 * Signature: (Ljava/lang/String;I)I
 */
SHAREDLIB_JNIEXPORT jint JNICALL Java_org_voltdb_jni_ExecutionEngine_hashinate__Ljava_lang_String_2I
  (JNIEnv *env, jobject, jstring value, jint partitionCount) {
    jsize length = env->GetStringUTFLength(value);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        return 0;
    }
    const char *string = env->GetStringUTFChars( value, NULL);
    if (string == NULL) {
        return 0;
    }
    int32_t retval = voltdb::TheHashinator::hashinate(string, length, partitionCount);
    env->ReleaseStringUTFChars(value, string);
    return retval;
}

/**
 * Turns on or off profiler.
 * @returns 0 on success.
 */
SHAREDLIB_JNIEXPORT jint JNICALL Java_org_voltdb_jni_ExecutionEngine_nativeToggleProfiler
(JNIEnv *env, jobject obj, jlong engine_ptr, jint toggle)
{
    VOLT_DEBUG("nativeToggleProfiler in C++ called");
    VoltDBEngine *engine = castToEngine(engine_ptr);
    updateJNILogProxy(engine); //JNIEnv pointer can change between calls, must be updated
    if (engine) {
        /*if (toggle) {
            ProfilerStart("/tmp/gprof.prof");
        }
        else {
            ProfilerStop();
        }*/
        return org_voltdb_jni_ExecutionEngine_ERRORCODE_SUCCESS;

    }
    return org_voltdb_jni_ExecutionEngine_ERRORCODE_ERROR;
}

/**
 * Release the undo token
 * @returns JNI_TRUE on success. JNI_FALSE otherwise.
 */
SHAREDLIB_JNIEXPORT jboolean JNICALL Java_org_voltdb_jni_ExecutionEngine_nativeReleaseUndoToken
(JNIEnv *env, jobject obj, jlong engine_ptr, jlong undoToken)
{
    VOLT_DEBUG("nativeReleaseUndoToken in C++ called");
    VoltDBEngine *engine = castToEngine(engine_ptr);
    Topend *topend = static_cast<JNITopend*>(engine->getTopend())->updateJNIEnv(env);
    try {
        updateJNILogProxy(engine); //JNIEnv pointer can change between calls, must be updated
        if (engine) {
            engine->releaseUndoToken(undoToken);
            return JNI_TRUE;
        }
    } catch (FatalException e) {
        topend->crashVoltDB(e);
    }
    return JNI_FALSE;
}

/**
 * Undo the undo token
 * @returns JNI_TRUE on success. JNI_FALSE otherwise.
 */
SHAREDLIB_JNIEXPORT jboolean JNICALL Java_org_voltdb_jni_ExecutionEngine_nativeUndoUndoToken
(JNIEnv *env, jobject obj, jlong engine_ptr, jlong undoToken)
{
    VOLT_DEBUG("nativeUndoUndoToken in C++ called");
    VoltDBEngine *engine = castToEngine(engine_ptr);
    Topend *topend = static_cast<JNITopend*>(engine->getTopend())->updateJNIEnv(env);
    try {
        updateJNILogProxy(engine); //JNIEnv pointer can change between calls, must be updated
        if (engine) {
            engine->undoUndoToken(undoToken);
            return JNI_TRUE;
        }
        return JNI_FALSE;
    } catch (FatalException e) {
        topend->crashVoltDB(e);
    }
    return false;
}

/*
 * Class:     org_voltdb_jni_ExecutionEngine
 * Method:    nativeSetLogLevels
 * Signature: (JJ)Z
 */
SHAREDLIB_JNIEXPORT jboolean JNICALL Java_org_voltdb_jni_ExecutionEngine_nativeSetLogLevels
  (JNIEnv *env, jobject obj, jlong engine_ptr, jlong logLevels) {
    VOLT_DEBUG("nativeSetLogLevels in C++ called");
    VoltDBEngine *engine = castToEngine(engine_ptr);
    Topend *topend = static_cast<JNITopend*>(engine->getTopend())->updateJNIEnv(env);
    try {
        updateJNILogProxy(engine); //JNIEnv pointer can change between calls, must be updated
        if (engine) {
            engine->getLogManager()->setLogLevels(logLevels);
        }
        return JNI_FALSE;
    } catch (FatalException e) {
        topend->crashVoltDB(e);
    }
    return false;
}

/*
 * Class:     org_voltdb_jni_ExecutionEngine
 * Method:    nativeActivateCopyOnWrite
 * Signature: (JI)Z
 */
SHAREDLIB_JNIEXPORT jboolean JNICALL Java_org_voltdb_jni_ExecutionEngine_nativeActivateCopyOnWrite
  (JNIEnv *env, jobject obj, jlong engine_ptr, jint tableId) {
    VOLT_DEBUG("nativeActivateCopyOnWrite in C++ called");
    VoltDBEngine *engine = castToEngine(engine_ptr);
    Topend *topend = static_cast<JNITopend*>(engine->getTopend())->updateJNIEnv(env);
    try {
        return engine->activateCopyOnWrite(tableId);
    } catch (FatalException e) {
        topend->crashVoltDB(e);
    }
    return false;
}

/*
 * Class:     org_voltdb_jni_ExecutionEngine
 * Method:    nativeCOWSerializeMore
 * Signature: (JJIII)I
 */
SHAREDLIB_JNIEXPORT jint JNICALL Java_org_voltdb_jni_ExecutionEngine_nativeCOWSerializeMore
  (JNIEnv *env,
   jobject obj,
   jlong engine_ptr,
   jlong bufferPtr,
   jint offset,
   jint length,
   jint tableId) {
    VOLT_DEBUG("nativeSetLogLevels in C++ called");
    ReferenceSerializeOutput out(reinterpret_cast<char*>(bufferPtr) + offset, length - offset);
    VoltDBEngine *engine = castToEngine(engine_ptr);
    Topend *topend = static_cast<JNITopend*>(engine->getTopend())->updateJNIEnv(env);
    try {
        try {
            return engine->cowSerializeMore( &out, tableId);
        } catch (SQLException e) {
            throwFatalException("%s", e.message().c_str());
        }
    } catch (FatalException e) {
        topend->crashVoltDB(e);
    }
    return 0;
}

/*
 * Class:     org_voltdb_jni_ExecutionEngine
 * Method:    nativeELTAction
 *
 * @param ackAction  true if this call contains an ack
 * @param pollAction true if this call requests a poll
 * @param ackOffset  if acking, the universal stream offset being acked/released
 * @param tableId    the table ID to which the ELT action applies
 *
 * @return the universal stream offset for the last octet in any
 * returned poll results (returned via the query results buffer).  On
 * any error this will be less than 0.  For any call with no
 * pollAction, any value >= 0 may be ignored.
 */
SHAREDLIB_JNIEXPORT jlong JNICALL Java_org_voltdb_jni_ExecutionEngine_nativeELTAction
  (JNIEnv *env,
   jobject obj,
   jlong engine_ptr,
   jboolean ackAction,
   jboolean pollAction,
   jlong ackOffset,
   jint tableId) {
    VOLT_DEBUG("nativeELTAction in C++ called");
    VoltDBEngine *engine = castToEngine(engine_ptr);
    Topend *topend = static_cast<JNITopend*>(engine->getTopend())->updateJNIEnv(env);
    try {
        try {
            engine->resetReusedResultOutputBuffer();
            return engine->eltAction(ackAction, pollAction, ackOffset, tableId);
        } catch (SQLException e) {
            throwFatalException("%s", e.message().c_str());
        }
    } catch (FatalException e) {
        topend->crashVoltDB(e);
    }
    return 0;
}

#ifdef LINUX
/*
 * Class:     org_voltdb_utils_ThreadUtils
 * Method:    getThreadAffinity
 * Signature: ()[Z
 */
SHAREDLIB_JNIEXPORT jbooleanArray JNICALL Java_org_voltdb_utils_ThreadUtils_getThreadAffinity
  (JNIEnv *env, jclass clazz) {
    /*
     * First get the affinity mask for this thread.
     */
    cpu_set_t mycpuid;
    CPU_ZERO(&mycpuid);
    sched_getaffinity(0, sizeof(mycpuid), &mycpuid);

    //Also get the total number of processors
    long int NUM_PROCS = sysconf(_SC_NPROCESSORS_CONF);

    /*
     * Create the Java array and get the memory region.
     */
    jbooleanArray returnArray = env->NewBooleanArray(static_cast<jsize>(NUM_PROCS));
    if (returnArray == NULL) {
        return NULL;
    }
    jboolean *boolArray = env->GetBooleanArrayElements(returnArray, NULL);
    if (boolArray == NULL) {
        return NULL;
    }

    for (int ii = 0; ii < NUM_PROCS; ii++) {
        if (CPU_ISSET(ii, &mycpuid)) {
            boolArray[ii] = JNI_TRUE;
        } else {
            boolArray[ii] = JNI_FALSE;
        }
    }

    /*
     * Now released and commit the boolArray
     */
    env->ReleaseBooleanArrayElements(returnArray, boolArray, 0);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        return NULL;
    }
    return returnArray;
}


/*
 * Class:     org_voltdb_utils_ThreadUtils
 * Method:    setThreadAffinity
 * Signature: ([Z)V
 */
SHAREDLIB_JNIEXPORT void JNICALL Java_org_voltdb_utils_ThreadUtils_setThreadAffinity
  (JNIEnv *env, jclass clazz, jbooleanArray coresArray) {
    jsize numCores = env->GetArrayLength(coresArray);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        return;
    }
    jboolean *cores = env->GetBooleanArrayElements(coresArray, NULL);
    if (cores == NULL) {
        return;
    }

    //Also get the total number of processors
    //sysconf(_SC_NPROCESSORS_CONF);
    //assert(numCores <= NUM_PROCS);

    cpu_set_t mask;
    CPU_ZERO(&mask);
    for (int ii = 0; ii < numCores; ii++) {
        if (JNI_TRUE == cores[ii]) {
            CPU_SET(ii, &mask);
        }
    }

    if ( sched_setaffinity( 0, sizeof(mask), &mask) == -1) {
        VOLT_ERROR("Couldn't set CPU affinity");
        assert(false);
    }
}

/*
 * Class:     org_voltdb_utils_ThreadUtils
 * Method:    getNumCores
 * Signature: ()I
 */
SHAREDLIB_JNIEXPORT jint JNICALL Java_org_voltdb_utils_ThreadUtils_getNumCores
  (JNIEnv *env, jclass clazz) {
    long int NUM_PROCS = sysconf(_SC_NPROCESSORS_CONF);
    return static_cast<jint>(NUM_PROCS);
}
#endif

/** @} */ // end of JNI doxygen group
