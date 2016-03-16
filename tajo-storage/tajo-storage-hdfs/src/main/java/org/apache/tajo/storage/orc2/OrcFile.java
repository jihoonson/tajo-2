/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tajo.storage.orc2;

public class OrcFile {

  public static final String MAGIC = "ORC";

  /**
   * Create a version number for the ORC file format, so that we can add
   * non-forward compatible changes in the future. To make it easier for users
   * to understand the version numbers, we use the Hive release number that
   * first wrote that version of ORC files.
   *
   * Thus, if you add new encodings or other non-forward compatible changes
   * to ORC files, which prevent the old reader from reading the new format,
   * you should change these variable to reflect the next Hive release number.
   * Non-forward compatible changes should never be added in patch releases.
   *
   * Do not make any changes that break backwards compatibility, which would
   * prevent the new reader from reading ORC files generated by any released
   * version of Hive.
   */
  public enum Version {
    V_0_11("0.11", 0, 11),
    V_0_12("0.12", 0, 12);

    public static final Version CURRENT = V_0_12;

    private final String name;
    private final int major;
    private final int minor;

    Version(String name, int major, int minor) {
      this.name = name;
      this.major = major;
      this.minor = minor;
    }

    public static Version byName(String name) {
      for(Version version: values()) {
        if (version.name.equals(name)) {
          return version;
        }
      }
      throw new IllegalArgumentException("Unknown ORC version " + name);
    }

    /**
     * Get the human readable name for the version.
     */
    public String getName() {
      return name;
    }

    /**
     * Get the major version number.
     */
    public int getMajor() {
      return major;
    }

    /**
     * Get the minor version number.
     */
    public int getMinor() {
      return minor;
    }
  }
}
