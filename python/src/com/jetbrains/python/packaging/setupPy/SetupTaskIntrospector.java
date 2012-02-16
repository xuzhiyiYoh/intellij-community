package com.jetbrains.python.packaging.setupPy;

import com.google.common.collect.ImmutableSet;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.packaging.PyPackageUtil;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.impl.PyQualifiedName;
import com.jetbrains.python.psi.resolve.PyResolveContext;
import com.jetbrains.python.psi.stubs.PyClassNameIndex;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * @author yole
 */
public class SetupTaskIntrospector {
  private static final Logger LOG = Logger.getInstance("#com.jetbrains.python.packaging.setupPy.SetupTaskIntrospector");

  private static final Map<String, List<SetupTask>> ourDistutilsTaskCache = new HashMap<String, List<SetupTask>>();
  private static final Map<String, List<SetupTask>> ourSetuptoolsTaskCache = new HashMap<String, List<SetupTask>>();

  public static List<AnAction> createSetupTaskActions(Module module, PyFile setupPyFile) {
    List<AnAction> result = new ArrayList<AnAction>();
    try {
      for (SetupTask task : getTaskList(module, usesSetuptools(setupPyFile))) {
        result.add(new RunSetupTaskAction(task.getName(), task.getName().replace("_", "__") + " (" + task.getDescription() + ")"));
      }
    }
    catch (Exception e) {
      LOG.error(e);
    }
    return result;
  }

  private static boolean usesSetuptools(PyFile file) {
    final List<PyFromImportStatement> imports = file.getFromImports();
    for (PyFromImportStatement anImport : imports) {
      final PyQualifiedName qName = anImport.getImportSourceQName();
      if (qName != null && qName.matches("setuptools")) {
        return true;
      }
    }

    final List<PyImportElement> importElements = file.getImportTargets();
    for (PyImportElement element : importElements) {
      final PyQualifiedName qName = element.getImportedQName();
      if (qName != null && qName.matches("setuptools")) {
        return true;
      }
    }
    return false;
  }

  @Nullable
  public static List<SetupTask.Option> getSetupTaskOptions(Module module, String taskName) {
    final PyFile setupPy = PyPackageUtil.findSetupPy(module);
    for (SetupTask task : getTaskList(module, setupPy != null && usesSetuptools(setupPy))) {
      if (task.getName().equals(taskName)) {
        return task.getOptions();
      }
    }
    return null;
  }

  private static List<SetupTask> getTaskList(Module module, boolean setuptools) {
    final String name = (setuptools ? "setuptools" : "distutils") + ".command.install.install";
    final Map<String, List<SetupTask>> cache = setuptools ? ourSetuptoolsTaskCache : ourDistutilsTaskCache;
    final PyClass installClass = PyClassNameIndex.findClass(name, module.getProject());
    if (installClass != null) {
      final PsiDirectory distutilsCommandDir = installClass.getContainingFile().getParent();
      if (distutilsCommandDir != null) {
        final String path = distutilsCommandDir.getVirtualFile().getPath();
        List<SetupTask> tasks = cache.get(path);
        if (tasks == null) {
          tasks = collectTasks(distutilsCommandDir, setuptools);
          cache.put(path, tasks);
        }
        return tasks;
      }
    }
    return Collections.emptyList();
  }

  private static final Set<String> SKIP_NAMES = ImmutableSet.of(PyNames.INIT_DOT_PY, "alias.py", "setopt.py", "savecfg.py");

  private static List<SetupTask> collectTasks(PsiDirectory dir, boolean setuptools) {
    List<SetupTask> result = new ArrayList<SetupTask>();
    for (PsiFile commandFile : dir.getFiles()) {
      if (commandFile instanceof PyFile && !SKIP_NAMES.contains(commandFile.getName())) {
        final String taskName = FileUtil.getNameWithoutExtension(commandFile.getName());
        result.add(createTaskFromFile((PyFile)commandFile, taskName, setuptools));
      }
    }
    return result;
  }

  private static SetupTask createTaskFromFile(PyFile file, String name, boolean setuptools) {
    SetupTask task = new SetupTask(name);
    // setuptools wraps the build_ext command class in a way that we cannot understand; use the distutils class which it delegates to
    final PyClass taskClass = (name.equals("build_ext") && setuptools)
                              ? PyClassNameIndex.findClass("distutils.command.build_ext.build_ext", file.getProject())
                              : file.findTopLevelClass(name);
    if (taskClass != null) {
      final PyTargetExpression description = taskClass.findClassAttribute("description", true);
      if (description != null) {
        final String descriptionText = PyUtil.strValue(PyUtil.flattenParens(description.findAssignedValue()));
        if (descriptionText != null) {
          task.setDescription(descriptionText);
        }
      }


      final List<PyExpression> booleanOptions = resolveSequenceValue(taskClass, "boolean_options");
      final List<String> booleanOptionsList = new ArrayList<String>();
      for (PyExpression option : booleanOptions) {
        final String s = PyUtil.strValue(option);
        if (s != null) {
          booleanOptionsList.add(s);
        }
      }

      final PyTargetExpression negativeOpt = taskClass.findClassAttribute("negative_opt", true);
      final Map<String, String> negativeOptMap = negativeOpt == null
                                                 ? Collections.<String, String>emptyMap()
                                                 : parseNegativeOpt(negativeOpt.findAssignedValue());


      final List<PyExpression> userOptions = resolveSequenceValue(taskClass, "user_options");
      for (PyExpression element : userOptions) {
        final SetupTask.Option option = createOptionFromTuple(element, booleanOptionsList, negativeOptMap);
        if (option != null) {
          task.addOption(option);
        }
      }
    }
    return task;
  }

  private static List<PyExpression> resolveSequenceValue(PyClass aClass, String name) {
    List<PyExpression> result = new ArrayList<PyExpression>();
    collectSequenceElements(aClass.findClassAttribute(name, true), result);
    return result;
  }

  private static void collectSequenceElements(PsiElement value, List<PyExpression> result) {
    if (value instanceof PySequenceExpression) {
      Collections.addAll(result, ((PySequenceExpression)value).getElements());
    }
    else if (value instanceof PyBinaryExpression) {
      final PyBinaryExpression binaryExpression = (PyBinaryExpression)value;
      if (binaryExpression.isOperator("+")) {
        collectSequenceElements(binaryExpression.getLeftExpression(), result);
        collectSequenceElements(binaryExpression.getRightExpression(), result);
      }
    }
    else if (value instanceof PyReferenceExpression) {
      final PsiElement resolveResult = ((PyReferenceExpression)value).getReference(PyResolveContext.noImplicits()).resolve();
      collectSequenceElements(resolveResult, result);
    }
    else if (value instanceof PyTargetExpression) {
      collectSequenceElements(((PyTargetExpression)value).findAssignedValue(), result);
    }
  }

  private static Map<String, String> parseNegativeOpt(PyExpression dict) {
    Map<String, String> result = new HashMap<String, String>();
    dict = PyUtil.flattenParens(dict);
    if (dict instanceof PyDictLiteralExpression) {
      final PyKeyValueExpression[] elements = ((PyDictLiteralExpression)dict).getElements();
      for (PyKeyValueExpression element : elements) {
        String key = PyUtil.strValue(PyUtil.flattenParens(element.getKey()));
        String value = PyUtil.strValue(PyUtil.flattenParens(element.getValue()));
        if (key != null && value != null) {
          result.put(key, value);
        }
      }
    }
    return result;
  }

  @Nullable
  private static SetupTask.Option createOptionFromTuple(PyExpression tuple, List<String> booleanOptions, Map<String, String> negativeOptMap) {
    tuple = PyUtil.flattenParens(tuple);
    if (tuple instanceof PyTupleExpression) {
      final PyExpression[] elements = ((PyTupleExpression)tuple).getElements();
      if (elements.length == 3) {
        String name = PyUtil.strValue(elements[0]);
        final String description = PyUtil.strValue(elements[2]);
        if (name != null && description != null) {
          if (negativeOptMap.containsKey(name)) {
            return null;
          }
          final boolean checkbox = booleanOptions.contains(name);
          boolean negative = false;
          if (negativeOptMap.containsValue(name)) {
            negative = true;
            for (Map.Entry<String, String> entry : negativeOptMap.entrySet()) {
              if (entry.getValue().equals(name)) {
                name = entry.getKey();
                break;
              }
            }
          }
          return new SetupTask.Option(name, StringUtil.capitalize(description), checkbox, negative);
        }
      }
    }
    return null;
  }
}
