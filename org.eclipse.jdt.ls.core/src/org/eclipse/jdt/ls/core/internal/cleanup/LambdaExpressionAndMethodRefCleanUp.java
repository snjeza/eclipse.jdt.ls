/*******************************************************************************
 * Copyright (c) 2023 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.cleanup;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.internal.corext.fix.CleanUpConstants;
import org.eclipse.jdt.internal.corext.fix.LambdaExpressionAndMethodRefFixCore;
import org.eclipse.jdt.ui.cleanup.CleanUpContext;
import org.eclipse.jdt.ui.cleanup.ICleanUpFix;

/**
 * Represents a cleanup that does several actions to clean up lambda expression
 */
public class LambdaExpressionAndMethodRefCleanUp implements ISimpleCleanUp {

	@Override
	public Collection<String> getIdentifiers() {
		return List.of("lambdaExpression", CleanUpConstants.SIMPLIFY_LAMBDA_EXPRESSION_AND_METHOD_REF);
	}

	@Override
	public ICleanUpFix createFix(CleanUpContext context) throws CoreException {
		CompilationUnit unit = context.getAST();
		if (unit == null) {
			return null;
		}
		return LambdaExpressionAndMethodRefFixCore.createCleanUp(unit);
	}

	@Override
	public List<String> getRequiredCompilerMarkers() {
		return Collections.emptyList();
	}

}
