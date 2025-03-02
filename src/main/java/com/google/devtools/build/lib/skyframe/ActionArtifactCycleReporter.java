// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.skyframe;

import com.google.common.base.Predicates;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.pkgcache.PackageProvider;
import com.google.devtools.build.lib.skyframe.TestCompletionValue.TestCompletionKey;
import com.google.devtools.build.skyframe.CycleInfo;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.function.Predicate;

/**
 * Reports cycles between Actions and Artifacts. These indicates cycles within a rule.
 */
public class ActionArtifactCycleReporter extends AbstractLabelCycleReporter {
  public static final Predicate<SkyKey> ACTION_OR_ARTIFACT_OR_TRANSITIVE_RDEP =
      Predicates.or(
          SkyFunctions.isSkyFunction(Artifact.ARTIFACT),
          SkyFunctions.isSkyFunction(SkyFunctions.ARTIFACT_NESTED_SET),
          SkyFunctions.isSkyFunction(SkyFunctions.ACTION_EXECUTION),
          SkyFunctions.isSkyFunction(SkyFunctions.TARGET_COMPLETION),
          SkyFunctions.isSkyFunction(SkyFunctions.ASPECT_COMPLETION),
          SkyFunctions.isSkyFunction(SkyFunctions.TEST_COMPLETION),
          SkyFunctions.isSkyFunction(SkyFunctions.BUILD_DRIVER));

  ActionArtifactCycleReporter(PackageProvider packageProvider) {
    super(packageProvider);
  }

  @Override
  protected String prettyPrint(Object untypedKey) {
    SkyKey key = (SkyKey) untypedKey;
    return prettyPrint(key.functionName(), key.argument());
  }

  private static String prettyPrint(SkyFunctionName skyFunctionName, Object arg) {
    if (arg instanceof Artifact) {
      return prettyPrintArtifact(((Artifact) arg));
    } else if (arg instanceof ActionLookupData) {
      return "action from: " + arg;
    } else if (arg instanceof TopLevelActionLookupKeyWrapper) {
      TopLevelActionLookupKeyWrapper key = (TopLevelActionLookupKeyWrapper) arg;
      if (skyFunctionName.equals(SkyFunctions.TARGET_COMPLETION)) {
        return "configured target: " + key.actionLookupKey().getLabel();
      }
      return "top-level aspect: "
          + ((AspectCompletionValue.AspectCompletionKey) key).actionLookupKey().prettyPrint();
    } else if (arg instanceof TestCompletionKey
        && skyFunctionName.equals(SkyFunctions.TEST_COMPLETION)) {
      return "test target: " + ((TestCompletionKey) arg).configuredTargetKey().getLabel();
    }
    throw new IllegalStateException(
        "Argument is not Action, TargetCompletion, AspectCompletion, or TestCompletion: " + arg);
  }

  private static String prettyPrintArtifact(Artifact artifact) {
    return "file: " + artifact.getRootRelativePathString();
  }

  @Override
  protected boolean shouldSkipOnPathToCycle(SkyKey key) {
    // BuildDriverKeys don't provide any relevant info for the end user.
    return SkyFunctions.BUILD_DRIVER.equals(key.functionName());
  }

  @Override
  protected Label getLabel(SkyKey key) {
    Object arg = key.argument();
    if (arg instanceof Artifact) {
      return ((Artifact) arg).getOwner();
    } else if (arg instanceof ActionLookupData) {
      return ((ActionLookupData) arg).getLabel();
    } else if (arg instanceof TopLevelActionLookupKeyWrapper) {
      return ((TopLevelActionLookupKeyWrapper) arg).actionLookupKey().getLabel();
    } else if (arg instanceof TestCompletionKey
        && key.functionName().equals(SkyFunctions.TEST_COMPLETION)) {
      return ((TestCompletionKey) arg).configuredTargetKey().getLabel();
    }
    throw new IllegalStateException(
        "Argument is not Action, TargetCompletion, AspectCompletion, or TestCompletion: " + arg);
  }

  @Override
  protected boolean canReportCycle(SkyKey topLevelKey, CycleInfo cycleInfo) {
    return ACTION_OR_ARTIFACT_OR_TRANSITIVE_RDEP.test(topLevelKey)
        && cycleInfo.getCycle().stream().allMatch(ACTION_OR_ARTIFACT_OR_TRANSITIVE_RDEP);
  }

  @Override
  protected boolean shouldSkipIntermediateKeyOnCycle(SkyKey key) {
    // ArtifactNestedSetKey isn't worth reporting to the user - it is just an optimization, and will
    // always be an intermediate member of a cycle. It may contain artifacts irrelevant to the
    // cycle, and may be nested several layers deep.
    return key instanceof ArtifactNestedSetKey;
  }
}
