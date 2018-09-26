/*******************************************************************************
 * Copyright (c) 2016-2017 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.managers;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.ProjectUtils;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.embedder.ICallable;
import org.eclipse.m2e.core.embedder.IMavenExecutionContext;
import org.eclipse.m2e.core.internal.MavenPluginActivator;
import org.eclipse.m2e.core.internal.embedder.MavenExecutionContext;
import org.eclipse.m2e.core.internal.embedder.MavenImpl;
import org.eclipse.m2e.core.internal.project.ProjectConfigurationManager;
import org.eclipse.m2e.core.internal.project.registry.ProjectRegistryManager;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.IMavenProjectRegistry;
import org.eclipse.m2e.core.project.IProjectConfigurationManager;
import org.eclipse.m2e.core.project.MavenUpdateRequest;
import org.eclipse.m2e.core.project.ResolverConfiguration;

/**
 * @author Fred Bricon
 *
 */
public class MavenBuildSupport implements IBuildSupport {
	private IProjectConfigurationManager configurationManager;
	private ProjectRegistryManager projectManager;
	private DigestStore digestStore;
	private IMavenProjectRegistry registry;
	private boolean shouldCollectProjects;

	public MavenBuildSupport() {
		this.configurationManager = MavenPlugin.getProjectConfigurationManager();
		this.projectManager = MavenPluginActivator.getDefault().getMavenProjectManagerImpl();
		this.digestStore = JavaLanguageServerPlugin.getDigestStore();
		this.registry = MavenPlugin.getMavenProjectRegistry();
		this.shouldCollectProjects = true;
	}

	@Override
	public boolean applies(IProject project) {
		return ProjectUtils.isMavenProject(project);
	}

	@Override
	public void update(IProject project, boolean force, IProgressMonitor monitor) throws CoreException {
		if (!applies(project)) {
			return;
		}
		Path pomPath = project.getFile("pom.xml").getLocation().toFile().toPath();
		if (digestStore.updateDigest(pomPath) || force) {
			JavaLanguageServerPlugin.logInfo("Starting Maven update for " + project.getName());
			if (shouldCollectProjects()) {
				IProject[] projects = collectProjects(project, monitor);
				MavenUpdateRequest request = new MavenUpdateRequest(projects, MavenPlugin.getMavenConfiguration().isOffline(), true);
				((ProjectConfigurationManager) configurationManager).updateProjectConfiguration(request, true, true, monitor);
			} else {
				MavenUpdateRequest request = new MavenUpdateRequest(project, MavenPlugin.getMavenConfiguration().isOffline(), true);
				configurationManager.updateProjectConfiguration(request, monitor);
			}
		}
	}

	public IProject[] collectProjects(IProject project, IProgressMonitor monitor) {
		if (!ProjectUtils.isMavenProject(project)) {
			return new IProject[0];
		}
		Set<IProject> projects = new LinkedHashSet<>();
		projects.add(project);
		List<File> files = new ArrayList<>();
		IFile pomResource = project.getFile("pom.xml");
		File file = pomResource.getLocation().toFile();
		files.add(file);
		try {
			ResolverConfiguration resolverConfiguration = configurationManager.getResolverConfiguration(project);
			if (resolverConfiguration == null) {
				return projects.toArray(new IProject[0]);
			}
			final MavenExecutionContext context = projectManager.createExecutionContext(pomResource, resolverConfiguration);
			context.execute(null, new ICallable<Void>() {
				@Override
				public Void call(IMavenExecutionContext context, IProgressMonitor monitor) throws CoreException {
					try {
						ProjectBuildingRequest projectBuildingRequest = context.newProjectBuildingRequest();
						MavenImpl maven = MavenPluginActivator.getDefault().getMaven();
						ProjectBuilder projectBuilder = maven.lookupComponent(ProjectBuilder.class);
						List<ProjectBuildingResult> results = projectBuilder.build(files, true, projectBuildingRequest);
						for (ProjectBuildingResult result : results) {
							File file = result.getPomFile();
							IWorkspace workspace= ResourcesPlugin.getWorkspace();
							IPath location= org.eclipse.core.runtime.Path.fromOSString(file.getAbsolutePath());
							IFile pomFile= workspace.getRoot().getFileForLocation(location);
							IMavenProjectFacade projectFacade = registry.create(pomFile, true, monitor);
							if (projectFacade != null) {
								projects.add(projectFacade.getProject());
							}
						}
					} catch (ProjectBuildingException e) {
						JavaLanguageServerPlugin.logException(e.getMessage(), e);
					}
					return null;
				}
			}, monitor);
		} catch (CoreException e) {
			JavaLanguageServerPlugin.logException(e.getMessage(), e);
		}
		return projects.toArray(new IProject[0]);
	}

	@Override
	public boolean isBuildFile(IResource resource) {
		return resource != null && resource.getProject() != null && resource.getType() == IResource.FILE && resource.getName().equals("pom.xml")
		//Check pom.xml is at the root of the project
				&& resource.getProject().equals(resource.getParent());
	}

	public boolean shouldCollectProjects() {
		return shouldCollectProjects;
	}

	public void setShouldCollectProjects(boolean shouldCollectProjects) {
		this.shouldCollectProjects = shouldCollectProjects;
	}
}
