/*
 * Copyright (c) 2007, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
#include <jni.h>
#include <stdio.h>
#include <time.h>
#include "ExceptionCheckingJniEnv.hpp"
#include "jni_tools.h"

extern "C" {

static jfieldID objFieldId = NULL;

/*
 * Class:     nsk_share_gc_lock_jni_DoubleArrayCriticalLocker
 * Method:    criticalNative
 * Signature: ([Z)Z
 */
JNIEXPORT jdouble JNICALL Java_nsk_share_gc_lock_jni_DoubleArrayCriticalLocker_criticalNative
(JNIEnv *jni_env, jobject o, jlong enterTime, jlong sleepTime) {
  ExceptionCheckingJniEnvPtr ec_jni(jni_env);

  jsize size, i;
  jdoubleArray arr;
  jdouble *pa;
  jdouble hash = 0;
  time_t start_time, current_time;

  if (objFieldId == NULL) {
    jclass klass = ec_jni->GetObjectClass(o);
    objFieldId = ec_jni->GetFieldID(klass, "obj", "Ljava/lang/Object;");
  }
  arr = (jdoubleArray) ec_jni->GetObjectField(o, objFieldId);
  ec_jni->SetObjectField(o, objFieldId, NULL);

  size = ec_jni->GetArrayLength(arr);
  start_time = time(NULL);
  enterTime /= 1000;
  current_time = 0;
  while (difftime(current_time, start_time) < enterTime) {
    hash = 0;
    pa = (jdouble*) ec_jni->GetPrimitiveArrayCritical(arr, NULL);
    if (pa != NULL) {
      for (i = 0; i < size; ++i) {
        hash += pa[i];
      }
    } else {
      jni_env->FatalError("GetPrimitiveArrayCritical returned NULL");
    }
    mssleep((long) sleepTime);
    ec_jni->ReleasePrimitiveArrayCritical(arr, pa, 0);
    mssleep((long) sleepTime);
    current_time = time(NULL);
  }
  ec_jni->SetObjectField(o, objFieldId, arr);
  return hash;
}

}
