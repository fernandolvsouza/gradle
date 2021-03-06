/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.plugins.ide.internal.tooling;

import com.google.common.collect.Lists;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.CompositeBuildIdeProjectResolver;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.plugins.ide.eclipse.EclipsePlugin;
import org.gradle.plugins.ide.eclipse.model.AbstractClasspathEntry;
import org.gradle.plugins.ide.eclipse.model.AbstractLibrary;
import org.gradle.plugins.ide.eclipse.model.AccessRule;
import org.gradle.plugins.ide.eclipse.model.BuildCommand;
import org.gradle.plugins.ide.eclipse.model.Classpath;
import org.gradle.plugins.ide.eclipse.model.ClasspathEntry;
import org.gradle.plugins.ide.eclipse.model.Container;
import org.gradle.plugins.ide.eclipse.model.EclipseClasspath;
import org.gradle.plugins.ide.eclipse.model.EclipseJdt;
import org.gradle.plugins.ide.eclipse.model.EclipseModel;
import org.gradle.plugins.ide.eclipse.model.Library;
import org.gradle.plugins.ide.eclipse.model.Link;
import org.gradle.plugins.ide.eclipse.model.Output;
import org.gradle.plugins.ide.eclipse.model.ProjectDependency;
import org.gradle.plugins.ide.eclipse.model.SourceFolder;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultAccessRule;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultClasspathAttribute;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultEclipseBuildCommand;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultEclipseClasspathContainer;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultEclipseExternalDependency;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultEclipseJavaSourceSettings;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultEclipseLinkedResource;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultEclipseOutputLocation;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultEclipseProject;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultEclipseProjectDependency;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultEclipseProjectNature;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultEclipseSourceDirectory;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultEclipseTask;
import org.gradle.plugins.ide.internal.tooling.java.DefaultInstalledJdk;
import org.gradle.tooling.internal.gradle.DefaultGradleProject;
import org.gradle.tooling.provider.model.internal.ProjectToolingModelBuilder;
import org.gradle.util.GUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class EclipseModelBuilder implements ProjectToolingModelBuilder {
    private final GradleProjectBuilder gradleProjectBuilder;
    private final CompositeBuildIdeProjectResolver compositeProjectMapper;

    private boolean projectDependenciesOnly;
    private DefaultEclipseProject result;
    private final Map<String, DefaultEclipseProject> projectMapping = new HashMap<String, DefaultEclipseProject>();
    private TasksFactory tasksFactory;
    private DefaultGradleProject<?> rootGradleProject;
    private Project currentProject;

    public EclipseModelBuilder(GradleProjectBuilder gradleProjectBuilder, ServiceRegistry services) {
        this.gradleProjectBuilder = gradleProjectBuilder;
        compositeProjectMapper = new CompositeBuildIdeProjectResolver(services);
    }

    @Override
    public boolean canBuild(String modelName) {
        return modelName.equals("org.gradle.tooling.model.eclipse.EclipseProject")
            || modelName.equals("org.gradle.tooling.model.eclipse.HierarchicalEclipseProject");
    }

    @Override
    public void addModels(String modelName, Project project, Map<String, Object> models) {
        DefaultEclipseProject eclipseProject = buildAll(modelName, project);
        addModels(eclipseProject, models);
    }

    private void addModels(DefaultEclipseProject eclipseProject, Map<String, Object> models) {
        models.put(eclipseProject.getPath(), eclipseProject);
        for (DefaultEclipseProject childProject : eclipseProject.getChildren()) {
            addModels(childProject, models);
        }
    }

    @Override
    public DefaultEclipseProject buildAll(String modelName, Project project) {
        boolean includeTasks = modelName.equals("org.gradle.tooling.model.eclipse.EclipseProject");
        tasksFactory = new TasksFactory(includeTasks);
        projectDependenciesOnly = modelName.equals("org.gradle.tooling.model.eclipse.HierarchicalEclipseProject");
        currentProject = project;
        Project root = project.getRootProject();
        rootGradleProject = gradleProjectBuilder.buildAll(project);
        tasksFactory.collectTasks(root);
        applyEclipsePlugin(root);
        buildHierarchy(root);
        populate(root);
        return result;
    }

    private void applyEclipsePlugin(Project root) {
        Set<Project> allProjects = root.getAllprojects();
        for (Project p : allProjects) {
            p.getPluginManager().apply(EclipsePlugin.class);
        }
        root.getPlugins().getPlugin(EclipsePlugin.class).performPostEvaluationActions();
    }

    private DefaultEclipseProject buildHierarchy(Project project) {
        List<DefaultEclipseProject> children = new ArrayList<DefaultEclipseProject>();
        for (Project child : project.getChildProjects().values()) {
            children.add(buildHierarchy(child));
        }

        EclipseModel eclipseModel = project.getExtensions().getByType(EclipseModel.class);
        org.gradle.plugins.ide.eclipse.model.EclipseProject internalProject = eclipseModel.getProject();
        String name = internalProject.getName();
        String description = GUtil.elvis(internalProject.getComment(), null);
        DefaultEclipseProject eclipseProject =
            new DefaultEclipseProject(name, project.getPath(), description, project.getProjectDir(), children)
                .setGradleProject(rootGradleProject.findByPath(project.getPath()));

        for (DefaultEclipseProject child : children) {
            child.setParent(eclipseProject);
        }
        addProject(project, eclipseProject);
        return eclipseProject;
    }

    private void addProject(Project project, DefaultEclipseProject eclipseProject) {
        if (project == currentProject) {
            result = eclipseProject;
        }
        projectMapping.put(project.getPath(), eclipseProject);
    }

    private void populate(Project project) {
        EclipseModel eclipseModel = project.getExtensions().getByType(EclipseModel.class);
        EclipseClasspath eclipseClasspath = eclipseModel.getClasspath();

        eclipseClasspath.setProjectDependenciesOnly(projectDependenciesOnly);

        List<ClasspathEntry> classpathEntries;
        if (eclipseClasspath.getFile() == null) {
            classpathEntries = eclipseClasspath.resolveDependencies();
        } else {
            Classpath classpath = new Classpath();
            eclipseClasspath.mergeXmlClasspath(classpath);
            classpathEntries = classpath.getEntries();
        }

        final List<DefaultEclipseExternalDependency> externalDependencies = new LinkedList<DefaultEclipseExternalDependency>();
        final List<DefaultEclipseProjectDependency> projectDependencies = new LinkedList<DefaultEclipseProjectDependency>();
        final List<DefaultEclipseSourceDirectory> sourceDirectories = new LinkedList<DefaultEclipseSourceDirectory>();
        final List<DefaultEclipseClasspathContainer> classpathContainers = new LinkedList<DefaultEclipseClasspathContainer>();
        DefaultEclipseOutputLocation outputLocation = null;

        for (ClasspathEntry entry : classpathEntries) {
            //we don't handle Variables at the moment because users didn't request it yet
            //and it would probably push us to add support in the tooling api to retrieve the variable mappings.
            if (entry instanceof Library) {
                AbstractLibrary library = (AbstractLibrary) entry;
                final File file = library.getLibrary().getFile();
                final File source = library.getSourcePath() == null ? null : library.getSourcePath().getFile();
                final File javadoc = library.getJavadocPath() == null ? null : library.getJavadocPath().getFile();
                DefaultEclipseExternalDependency dependency = new DefaultEclipseExternalDependency(file, javadoc, source, library.getModuleVersion(), library.isExported(), createAttributes(library), createAccessRules(library));
                externalDependencies.add(dependency);
            } else if (entry instanceof ProjectDependency) {
                final ProjectDependency projectDependency = (ProjectDependency) entry;
                final String path = StringUtils.removeStart(projectDependency.getPath(), "/");
                DefaultEclipseProject targetProject = projectMapping.get(projectDependency.getGradlePath());
                DefaultEclipseProjectDependency dependency;
                if (targetProject == null) {
                    File projectDirectory = compositeProjectMapper.getProjectDirectory(projectDependency.getGradlePath());
                    dependency = new DefaultEclipseProjectDependency(path, projectDirectory, projectDependency.isExported(), createAttributes(projectDependency), createAccessRules(projectDependency));
                } else {
                    dependency = new DefaultEclipseProjectDependency(path, targetProject, projectDependency.isExported(), createAttributes(projectDependency), createAccessRules(projectDependency));
                }
                projectDependencies.add(dependency);
            } else if (entry instanceof SourceFolder) {
                final SourceFolder sourceFolder = (SourceFolder) entry;
                String path = sourceFolder.getPath();
                List<String> excludes = sourceFolder.getExcludes();
                List<String> includes = sourceFolder.getIncludes();
                String output = sourceFolder.getOutput();
                sourceDirectories.add(new DefaultEclipseSourceDirectory(path, sourceFolder.getDir(), excludes, includes, output, createAttributes(sourceFolder), createAccessRules(sourceFolder)));
            } else if (entry instanceof Container) {
                final Container container = (Container) entry;
                classpathContainers.add(new DefaultEclipseClasspathContainer(container.getPath(), createAttributes(container), createAccessRules(container)));
            } else if (entry instanceof Output) {
                outputLocation = new DefaultEclipseOutputLocation(((Output)entry).getPath());
            }
        }

        DefaultEclipseProject eclipseProject = projectMapping.get(project.getPath());
        eclipseProject.setClasspath(externalDependencies);
        eclipseProject.setProjectDependencies(projectDependencies);
        eclipseProject.setSourceDirectories(sourceDirectories);

        List<DefaultEclipseLinkedResource> linkedResources = new LinkedList<DefaultEclipseLinkedResource>();
        for (Link r : eclipseModel.getProject().getLinkedResources()) {
            linkedResources.add(new DefaultEclipseLinkedResource(r.getName(), r.getType(), r.getLocation(), r.getLocationUri()));
        }
        eclipseProject.setLinkedResources(linkedResources);

        List<DefaultEclipseTask> tasks = new ArrayList<DefaultEclipseTask>();
        for (Task t : tasksFactory.getTasks(project)) {
            tasks.add(new DefaultEclipseTask(eclipseProject, t.getPath(), t.getName(), t.getDescription()));
        }
        eclipseProject.setTasks(tasks);

        List<DefaultEclipseProjectNature> natures = new ArrayList<DefaultEclipseProjectNature>();
        for(String n: eclipseModel.getProject().getNatures()) {
            natures.add(new DefaultEclipseProjectNature(n));
        }
        eclipseProject.setProjectNatures(natures);

        List<DefaultEclipseBuildCommand> buildCommands = new ArrayList<DefaultEclipseBuildCommand>();
        for (BuildCommand b : eclipseModel.getProject().getBuildCommands()) {
            buildCommands.add(new DefaultEclipseBuildCommand(b.getName(), b.getArguments()));
        }
        eclipseProject.setBuildCommands(buildCommands);
        EclipseJdt jdt = eclipseModel.getJdt();
        if (jdt != null) {
            eclipseProject.setJavaSourceSettings(new DefaultEclipseJavaSourceSettings()
                .setSourceLanguageLevel(jdt.getSourceCompatibility())
                .setTargetBytecodeVersion(jdt.getTargetCompatibility())
                .setJdk(DefaultInstalledJdk.current())
            );
        }

        eclipseProject.setClasspathContainers(classpathContainers);

        eclipseProject.setOutputLocation(outputLocation != null ? outputLocation : new DefaultEclipseOutputLocation("bin"));

        for (Project childProject : project.getChildProjects().values()) {
            populate(childProject);
        }
    }

    private static List<DefaultClasspathAttribute> createAttributes(AbstractClasspathEntry classpathEntry) {
        List<DefaultClasspathAttribute> result = Lists.newArrayList();
        Map<String, Object> attributes = classpathEntry.getEntryAttributes();
        attributes.entrySet();
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            Object value = entry.getValue();
            result.add(new DefaultClasspathAttribute(entry.getKey(), value == null ? "" : value.toString()));
        }
        return result;
    }

    private static List<DefaultAccessRule> createAccessRules(AbstractClasspathEntry classpathEntry) {
        List<DefaultAccessRule> result = Lists.newArrayList();
        for(AccessRule accessRule : classpathEntry.getAccessRules()) {
            result.add(new DefaultAccessRule(Integer.parseInt(accessRule.getKind()), accessRule.getPattern()));
        }
        return result;
    }
}
