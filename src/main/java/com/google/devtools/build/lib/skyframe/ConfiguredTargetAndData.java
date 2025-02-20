// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import javax.annotation.Nullable;

/**
 * A container class for a {@link ConfiguredTarget} and associated data, {@link Target}, {@link
 * BuildConfigurationValue}, and transition keys. In the future, {@link ConfiguredTarget} objects
 * will no longer contain their associated {@link BuildConfigurationValue}. Consumers that need the
 * {@link Target} or {@link BuildConfigurationValue} must therefore have access to one of these
 * objects.
 *
 * <p>These objects are intended to be short-lived, never stored in Skyframe, since they pair three
 * heavyweight objects, a {@link ConfiguredTarget}, a {@link Target} (which holds a {@link
 * com.google.devtools.build.lib.packages.Package}), and a {@link BuildConfigurationValue}.
 */
public class ConfiguredTargetAndData {
  private final ConfiguredTarget configuredTarget;
  private final Target target;
  private final BuildConfigurationValue configuration;
  private final ImmutableList<String> transitionKeys;

  @VisibleForTesting
  public ConfiguredTargetAndData(
      ConfiguredTarget configuredTarget,
      Target target,
      BuildConfigurationValue configuration,
      ImmutableList<String> transitionKeys) {
    this(configuredTarget, target, configuration, transitionKeys, /*checkConsistency=*/ true);
  }

  private ConfiguredTargetAndData(
      ConfiguredTarget configuredTarget,
      Target target,
      BuildConfigurationValue configuration,
      ImmutableList<String> transitionKeys,
      boolean checkConsistency) {
    this.configuredTarget = configuredTarget;
    this.target = target;
    this.configuration = configuration;
    this.transitionKeys = transitionKeys;
    if (!checkConsistency) {
      return;
    }
    Preconditions.checkState(
        configuredTarget.getLabel().equals(target.getLabel()),
        "Unable to construct ConfiguredTargetAndData:"
            + " ConfiguredTarget's label %s is not equal to Target's label %s",
        configuredTarget.getLabel(),
        target.getLabel());
    BuildConfigurationKey innerConfigurationKey = configuredTarget.getConfigurationKey();
    if (configuration == null) {
      Preconditions.checkState(
          innerConfigurationKey == null,
          "Non-null configuration key for %s but configuration is null (%s)",
          configuredTarget,
          target);
    } else {
      BuildConfigurationKey configurationKey = configuration.getKey();
      Preconditions.checkState(
          innerConfigurationKey.equals(configurationKey),
          "Configurations don't match: %s %s %s (%s %s)",
          configurationKey,
          innerConfigurationKey,
          configuration,
          configuredTarget,
          target);
    }
  }

  @Nullable
  static ConfiguredTargetAndData fromConfiguredTargetInSkyframe(
      ConfiguredTarget ct, SkyFunction.Environment env) throws InterruptedException {
    BuildConfigurationValue configuration = null;
    ImmutableSet<SkyKey> packageAndMaybeConfiguration;
    PackageValue.Key packageKey = PackageValue.key(ct.getLabel().getPackageIdentifier());
    BuildConfigurationKey configurationKeyMaybe = ct.getConfigurationKey();
    if (configurationKeyMaybe == null) {
      packageAndMaybeConfiguration = ImmutableSet.of(packageKey);
    } else {
      packageAndMaybeConfiguration = ImmutableSet.of(packageKey, configurationKeyMaybe);
    }
    SkyframeLookupResult packageAndMaybeConfigurationValues =
        env.getValuesAndExceptions(packageAndMaybeConfiguration);
    // Don't test env.valuesMissing(), because values may already be missing from the caller.
    PackageValue packageValue = (PackageValue) packageAndMaybeConfigurationValues.get(packageKey);
    if (packageValue == null) {
      return null;
    }
    if (configurationKeyMaybe != null) {
      configuration =
          (BuildConfigurationValue) packageAndMaybeConfigurationValues.get(configurationKeyMaybe);
      if (configuration == null) {
        return null;
      }
    }
    try {
      return new ConfiguredTargetAndData(
          ct, packageValue.getPackage().getTarget(ct.getLabel().getName()), configuration, null);
    } catch (NoSuchTargetException e) {
      throw new IllegalStateException("Failed to retrieve target for " + ct, e);
    }
  }

  /**
   * For use with {@code MergedConfiguredTarget} and similar, where we create a virtual {@link
   * ConfiguredTarget} corresponding to the same {@link Target}.
   */
  public ConfiguredTargetAndData fromConfiguredTarget(ConfiguredTarget maybeNew) {
    if (configuredTarget.equals(maybeNew)) {
      return this;
    }
    return new ConfiguredTargetAndData(maybeNew, target, configuration, transitionKeys);
  }

  /**
   * Variation of {@link #fromConfiguredTarget} that doesn't check the new target has the same
   * configuration as the original.
   *
   * <p>Intended for trimming (like {@code --trim_test_configuration}).
   */
  public ConfiguredTargetAndData fromConfiguredTargetNoCheck(ConfiguredTarget maybeNew) {
    if (configuredTarget.equals(maybeNew)) {
      return this;
    }
    return new ConfiguredTargetAndData(maybeNew, target, configuration, transitionKeys, false);
  }

  public Target getTarget() {
    return target;
  }

  public BuildConfigurationValue getConfiguration() {
    return configuration;
  }

  public ConfiguredTarget getConfiguredTarget() {
    return configuredTarget;
  }

  public ImmutableList<String> getTransitionKeys() {
    return transitionKeys;
  }
}
