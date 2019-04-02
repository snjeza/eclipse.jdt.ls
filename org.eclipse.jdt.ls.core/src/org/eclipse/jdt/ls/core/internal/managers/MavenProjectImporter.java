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
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.ls.core.internal.AbstractProjectImporter;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.ProjectUtils;
import org.eclipse.jdt.ls.core.internal.preferences.PreferenceManager;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.embedder.MavenModelManager;
import org.eclipse.m2e.core.internal.MavenPluginActivator;
import org.eclipse.m2e.core.internal.preferences.MavenConfigurationImpl;
import org.eclipse.m2e.core.internal.preferences.ProblemSeverity;
import org.eclipse.m2e.core.project.IMavenProjectImportResult;
import org.eclipse.m2e.core.project.IProjectConfigurationManager;
import org.eclipse.m2e.core.project.LocalProjectScanner;
import org.eclipse.m2e.core.project.MavenProjectInfo;
import org.eclipse.m2e.core.project.ProjectImportConfiguration;
import org.eclipse.m2e.jdt.IClasspathManager;
import org.eclipse.m2e.jdt.MavenJdtPlugin;

@SuppressWarnings("restriction")
public class MavenProjectImporter extends AbstractProjectImporter {

	private static final int MAX_PROJECTS_TO_IMPORT = 10;

	public static final String IMPORTING_MAVEN_PROJECTS = "Importing Maven project(s)";

	public static final String POM_FILE = "pom.xml";

	private Set<MavenProjectInfo> projectInfos = null;

	private IProjectConfigurationManager configurationManager;
	private DigestStore digestStore;

	public MavenProjectImporter() {
		this.configurationManager = MavenPlugin.getProjectConfigurationManager();
		this.digestStore = JavaLanguageServerPlugin.getDigestStore();
	}


	@Override
	public boolean applies(IProgressMonitor monitor) throws OperationCanceledException, CoreException {
		PreferenceManager preferencesManager = JavaLanguageServerPlugin.getPreferencesManager();
		if (preferencesManager != null && !preferencesManager.getPreferences().isImportMavenEnabled()) {
			return false;
		}
		Set<MavenProjectInfo> files = getMavenProjectInfo(monitor);
		if (files != null) {
			Iterator<MavenProjectInfo> iter = files.iterator();
			while (iter.hasNext()) {
				MavenProjectInfo projectInfo = iter.next();
				File dir = projectInfo.getPomFile() == null ? null : projectInfo.getPomFile().getParentFile();
				if (dir != null && exclude(dir.toPath())) {
					iter.remove();
				}
			}
		}
		return files != null && !files.isEmpty();
	}

	private boolean exclude(java.nio.file.Path path) {
		List<String> javaImportExclusions = JavaLanguageServerPlugin.getPreferencesManager().getPreferences().getJavaImportExclusions();
		if (javaImportExclusions != null) {
			for (String pattern : javaImportExclusions) {
				PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
				if (matcher.matches(path)) {
					return true;
				}
			}
		}
		return false;
	}

	synchronized Set<MavenProjectInfo> getMavenProjectInfo(IProgressMonitor monitor) throws OperationCanceledException {
		if (projectInfos == null) {
			projectInfos = collectMavenProjectInfo(monitor);
		}
		return projectInfos;
	}

	Set<MavenProjectInfo> collectMavenProjectInfo(IProgressMonitor monitor) throws OperationCanceledException {
		MavenModelManager modelManager = MavenPlugin.getMavenModelManager();
		return getMavenProjects(getProjectDirectory(), modelManager, monitor);
	}

	@Override
	public void reset() {
		projectInfos = null;
	}

	@Override
	public void importToWorkspace(IProgressMonitor monitor) throws CoreException, OperationCanceledException {
		JavaLanguageServerPlugin.logInfo(IMPORTING_MAVEN_PROJECTS);
		MavenConfigurationImpl configurationImpl = (MavenConfigurationImpl)MavenPlugin.getMavenConfiguration();
		configurationImpl.setDownloadSources(false);
		configurationImpl.setNotCoveredMojoExecutionSeverity(ProblemSeverity.ignore.toString());
		SubMonitor subMonitor = SubMonitor.convert(monitor, 105);
		subMonitor.setTaskName(IMPORTING_MAVEN_PROJECTS);
		Set<MavenProjectInfo> files = getMavenProjectInfo(subMonitor.split(5));
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		Collection<IProject> projects = new LinkedHashSet<>();
		Collection<MavenProjectInfo> toImport = new LinkedHashSet<>();
		long lastWorkspaceStateSaved = getLastWorkspaceStateModified();
		//Separate existing projects from new ones
		for (MavenProjectInfo projectInfo : files) {
			File pom = projectInfo.getPomFile();
			IContainer container = root.getContainerForLocation(new Path(pom.getAbsolutePath()));
			if (container == null) {
				digestStore.updateDigest(pom.toPath());
				toImport.add(projectInfo);
			} else {
				IProject project = container.getProject();
				if (ProjectUtils.isMavenProject(project)) {
					projects.add(container.getProject());
				} else if (project != null) {
					//Project doesn't have the Maven nature, so we (re)import it
					digestStore.updateDigest(pom.toPath());
					// need to delete project due to m2e failing to create if linked and not the same name
					project.delete(IProject.FORCE | IProject.NEVER_DELETE_PROJECT_CONTENT, subMonitor.split(5));
					toImport.add(projectInfo);
				}
			}
		}
		if (!toImport.isEmpty()) {
			boolean downloadSources = JavaLanguageServerPlugin.getPreferencesManager().getPreferences().isMavenDownloadSources();
			if (toImport.size() > MAX_PROJECTS_TO_IMPORT) {
				JavaLanguageServerPlugin.logInfo("Projects size:" + toImport.size());
				Iterator<MavenProjectInfo> iter = toImport.iterator();
				int it = 0;
				List<IMavenProjectImportResult> results = new ArrayList<>();
				SubMonitor monitor2 = SubMonitor.convert(monitor, projects.size() * 2);
				while (iter.hasNext()) {
					List<MavenProjectInfo> importPartial = new ArrayList<>();
					int i = 0;
					while (i++ < MAX_PROJECTS_TO_IMPORT && iter.hasNext()) {
						importPartial.add(iter.next());
					}
					ProjectImportConfiguration importConfig = new ProjectImportConfiguration();
					List<IMavenProjectImportResult> result = configurationManager.importProjects(importPartial, importConfig, monitor2.split(MAX_PROJECTS_TO_IMPORT));
					results.addAll(result);
					if (downloadSources) {
						for (IMavenProjectImportResult r : results) {
							downloadSources(r.getProject(), true, false);
						}
					}
					monitor2.worked(MAX_PROJECTS_TO_IMPORT);
				}
				List<IProject> imported = new ArrayList<>(results.size());
				for (IMavenProjectImportResult result : results) {
					imported.add(result.getProject());
				}
				JavaLanguageServerPlugin.logInfo("Update projects:" + it++);
				updateProjects(imported, lastWorkspaceStateSaved, monitor2);
				monitor2.done();
			} else {
				ProjectImportConfiguration importConfig = new ProjectImportConfiguration();
				List<IMavenProjectImportResult> results = configurationManager.importProjects(toImport, importConfig, subMonitor.split(75));
				if (downloadSources) {
					for (IMavenProjectImportResult result : results) {
						downloadSources(result.getProject(), true, false);
					}
				}
			}
		}
		subMonitor.setWorkRemaining(20);
		updateProjects(projects, lastWorkspaceStateSaved, subMonitor.split(20));
		subMonitor.done();
	}

	private long getLastWorkspaceStateModified() {
		File workspaceStateFile = MavenPluginActivator.getDefault().getMavenProjectManager().getWorkspaceStateFile();
		return workspaceStateFile.lastModified();
	}

	private File getProjectDirectory() {
		return rootFolder;
	}

	private void updateProjects(Collection<IProject> projects, long lastWorkspaceStateSaved, IProgressMonitor monitor) throws CoreException {
		if (projects.isEmpty()) {
			return;
		}
		Iterator<IProject> iterator = projects.iterator();
		while (iterator.hasNext()) {
			IProject project = iterator.next();
			project.open(monitor);
			project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
			if (!needsMavenUpdate(project, lastWorkspaceStateSaved)) {
				iterator.remove();
			}
		}
		if (projects.isEmpty()) {
			return;
		}

		new WorkspaceJob("Update Maven project configuration") {
			@Override
			public IStatus runInWorkspace(IProgressMonitor monitor) throws CoreException {
				MavenBuildSupport mavenBuildSupport = new MavenBuildSupport();
				mavenBuildSupport.setShouldCollectProjects(false);
				for (IProject project : projects) {
					if (monitor.isCanceled()) {
						return Status.CANCEL_STATUS;
					}
					mavenBuildSupport.update(project, false, monitor);
				}
				return Status.OK_STATUS;
			}
		}.schedule();
	}

	private boolean needsMavenUpdate(IProject project, long lastWorkspaceStateSaved) {
		return project.getFile(POM_FILE).getLocalTimeStamp() > lastWorkspaceStateSaved;
	}

	private Set<MavenProjectInfo> getMavenProjects(File directory, MavenModelManager modelManager, IProgressMonitor monitor) throws OperationCanceledException {
		if (directory == null) {
			return Collections.emptySet();
		}
		try {
			LocalProjectScanner scanner = new LocalProjectScanner(directory.getParentFile(), directory.toString(), false, modelManager);
			scanner.run(monitor);
			return collectProjects(scanner.getProjects());
		} catch (InterruptedException e) {
			throw new OperationCanceledException();
		}
	}

	public boolean isMavenProject() {
		return  isMavenProject(getProjectDirectory());
	}

	private boolean isMavenProject(File dir) {
		if (!isReadable(dir)
				|| !dir.isDirectory()) {
			return false;
		}
		return isReadable(new File(dir, POM_FILE));
	}

	private boolean isReadable(File destination) {
		return destination != null
				&& destination.canRead();
	}

	public Set<MavenProjectInfo> collectProjects(
			Collection<MavenProjectInfo> projects) {
		return new LinkedHashSet<MavenProjectInfo>() {
			private static final long serialVersionUID = 1L;

			public Set<MavenProjectInfo> collectProjects(
					Collection<MavenProjectInfo> projects) {
				for (MavenProjectInfo projectInfo : projects) {
					add(projectInfo);
					collectProjects(projectInfo.getProjects());
				}
				return this;
			}
		}.collectProjects(projects);
	}

	private void downloadSources(IProject project, boolean downloadSources, boolean downloadJavadoc) {
		IClasspathManager buildpathManager = MavenJdtPlugin.getDefault().getBuildpathManager();
		buildpathManager.scheduleDownload(project, downloadSources, downloadJavadoc);
	}

}
