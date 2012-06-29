/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.jetbrains.plugins.groovy;


import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightCodeInsightFixtureTestCase
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.NotNull

import static org.jetbrains.plugins.groovy.util.TestUtils.getMockGroovy1_8LibraryName

/**
 * @author peter
 */
public abstract class LightGroovyTestCase extends LightCodeInsightFixtureTestCase {
  public static final LightProjectDescriptor GROOVY_DESCRIPTOR = new DefaultLightProjectDescriptor() {
    @Override
    public void configureModule(Module module, ModifiableRootModel model, ContentEntry contentEntry) {
      final Library.ModifiableModel modifiableModel = model.moduleLibraryTable.createLibrary("GROOVY").modifiableModel;
      final VirtualFile groovyJar = JarFileSystem.instance.refreshAndFindFileByPath("$mockGroovy1_8LibraryName!/");
      assert groovyJar != null;
      modifiableModel.addRoot(groovyJar, OrderRootType.CLASSES);
      modifiableModel.commit();
    }
  };

  @Override
  @NotNull
  protected LightProjectDescriptor getProjectDescriptor() {
    return GROOVY_DESCRIPTOR;
  }

  /**
   * Return relative path to the test data. Path is relative to the
   * {@link com.intellij.openapi.application.PathManager#getHomePath()}
   *
   * @return relative path to the test data.
   */
  @Override
  @NonNls
  protected abstract String getBasePath();


  protected void addGroovyTransformField() {
    myFixture.addClass('''package groovy.transform; public @interface Field{}''');
  }

  protected void addGroovyObject() throws IOException {
    myFixture.addClass('''\
package groovy.lang;
public interface GroovyObject {
    java.lang.Object invokeMethod(java.lang.String s, java.lang.Object o);
    java.lang.Object getProperty(java.lang.String s);
    void setProperty(java.lang.String s, java.lang.Object o);
    groovy.lang.MetaClass getMetaClass();
    void setMetaClass(groovy.lang.MetaClass metaClass);
}
''');
  }

  void addCompileStatic() {
   myFixture.addClass('''\
package groovy.transform;
public @interface CompileStatic{
}
''')
  }
}
