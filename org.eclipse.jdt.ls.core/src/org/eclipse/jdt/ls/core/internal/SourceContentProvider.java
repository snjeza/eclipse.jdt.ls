/*******************************************************************************
 * Copyright (c) 2017 David Gileadi and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     David Gileadi - initial API
 *     Red Hat Inc. - initial implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal;

import java.net.URI;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.m2e.jdt.IClasspathManager;
import org.eclipse.m2e.jdt.MavenJdtPlugin;

public class SourceContentProvider implements IDecompiler {

	private static final long MAX_WAIT = 3000;

	@Override
	public String getContent(URI uri, IProgressMonitor monitor) throws CoreException {
		IClassFile classFile = JDTUtils.resolveClassFile(uri);
		if (classFile != null) {
			return getSource(classFile, monitor);
		}
		return null;
	}

	@Override
	public String getSource(IClassFile classFile, IProgressMonitor monitor) throws CoreException {
		String source = null;
		try {
			IBuffer buffer = classFile.getBuffer();
			if (buffer == null) {
				if (ProjectUtils.isMavenProject(classFile.getJavaProject().getProject())) {
					IJavaElement element = classFile;
					while (element.getParent() != null) {
						element = element.getParent();
						if (element instanceof IPackageFragmentRoot) {
							final IPackageFragmentRoot fragment = (IPackageFragmentRoot) element;
							IPath attachmentPath = fragment.getSourceAttachmentPath();
							if (attachmentPath != null && !attachmentPath.isEmpty() && attachmentPath.toFile().exists()) {
								break;
							}
							if (fragment.isArchive()) {
								IClasspathManager buildpathManager = MavenJdtPlugin.getDefault().getBuildpathManager();
								buildpathManager.scheduleDownload(fragment, true, false);
								waitForDownload();
								buffer = classFile.getBuffer();
								if (buffer != null) {
									if (monitor.isCanceled()) {
										return null;
									}
									source = buffer.getContents();
									JavaLanguageServerPlugin.logInfo("ClassFile contents request completed");
								}
							}
						}
					}
				}
			}
			if (buffer != null) {
				if (monitor.isCanceled()) {
					return null;
				}
				source = buffer.getContents();
				JavaLanguageServerPlugin.logInfo("ClassFile contents request completed");
			}
		} catch (JavaModelException e) {
			JavaLanguageServerPlugin.logException("Exception getting java element ", e);
		}
		return source;
	}

	private void waitForDownload() {
		final long limit = System.currentTimeMillis() + MAX_WAIT;
		while (true) {
			Job[] jobs = Job.getJobManager().find(null);
			Job job = null;
			for (Job j : jobs) {
				if ("org.eclipse.m2e.jdt.internal.DownloadSourcesJob".equals(j.getClass().getName())) {
					job = j;
					break;
				}
			}
			if (job == null) {
				break;
			}
			boolean timeout = System.currentTimeMillis() > limit;
			if (timeout) {
				JavaLanguageServerPlugin.logInfo("Timeout while waiting for completion of job: " + job);
				break;
			}
			job.wakeUp();
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				// ignore and keep waiting
			}
		}
	}

}
