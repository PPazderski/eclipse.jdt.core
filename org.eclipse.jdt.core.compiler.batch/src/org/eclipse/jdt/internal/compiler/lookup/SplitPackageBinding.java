/*******************************************************************************
 * Copyright (c) 2017, 2019 GK Software SE, and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Stephan Herrmann - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.compiler.lookup;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.jdt.core.compiler.CharOperation;

public class SplitPackageBinding extends PackageBinding {
	Set<ModuleBinding> declaringModules;
	public Set<PlainPackageBinding> incarnations;
	private boolean supressAddLog = true;
	private static ThreadLocal<Integer> logIndent = ThreadLocal.withInitial(() -> Integer.valueOf(0));
	public SplitPackageBinding currentFind = null;

	/** TEST ONLY */
	public static Consumer<SplitPackageBinding> instanceListener;

	/**
	 * Combine two potential package bindings, answering either the better of those if the other has a problem,
	 * or combine both into a split package.
	 * @param binding one candidate
	 * @param previous a previous candidate
	 * @param primaryModule when constructing a new SplitPackageBinding this primary module will define the
	 * 	focus when later an UnresolvedReferenceBinding is resolved relative to this SplitPackageBinding.
	 * @return one of: <code>null</code>, a regular PackageBinding or a SplitPackageBinding.
	 */
	public static PackageBinding combine(PackageBinding binding, PackageBinding previous, ModuleBinding primaryModule) {
		// if a candidate has problems, pick the "better" candidate:
		int prevRank = rank(previous);
		int curRank = rank(binding);
		if (prevRank < curRank)
			return binding;
		if (prevRank > curRank)
			return previous;
		if (previous == null)
			return null;
		// both are valid
		if (previous.subsumes(binding))
			return previous;
		if (binding.subsumes(previous))
			return binding;
		log("Combine bindings %s", format(previous));
		incLogIndent();
		log("and %s", format(binding));
		log("for primary module %s", format(primaryModule));
		SplitPackageBinding split = new SplitPackageBinding(previous, primaryModule);
		split.add(binding);
		log("New split binding %s", format(split));
		decLogIndent();
		split.supressAddLog = false;
		return split;
	}
	public static PackageBinding combineAll(List<PackageBinding> bindings, ModuleBinding primaryModule) {
		log("Combine all %d bindings for primary module %s", bindings.size(), format(primaryModule));
		log("Called from: %s", new Exception().getStackTrace()[1]);
		incLogIndent();
		for (PackageBinding binding : bindings) {
			log(format(binding));
		}
		// collect statistics per rank:
		int[] numRanked = new int[RANK_VALID+1];
		for (PackageBinding packageBinding : bindings) {
			int rank = rank(packageBinding);
			numRanked[rank]++;
		}
		try {
		SplitPackageBinding split = null;
		for (int rank = RANK_VALID; rank >= 0; rank--) {
			int num = numRanked[rank];
			if (num > 0) {
				// rank is the best we have, so take all bindings at this rank:
				for (PackageBinding packageBinding : bindings) {
					if (rank(packageBinding) == rank) {
						if (num == 1 || rank != RANK_VALID) {
							log("Combined all with rank %d as early singleton %s", rank, format(packageBinding));
							return packageBinding;	// singleton, problem & null don't need SplitPackageBinding
						}
						// finally collect all relevant:
						if (split == null)
							split = new SplitPackageBinding(packageBinding, primaryModule);
						else
							split.add(packageBinding);
					}
				}
				if (split.incarnations.size() == 1) { // we don't want singleton SplitPackageBinding
					log("Combined all with rank %d as late singleton %s", rank, format(split.incarnations.iterator().next()));
					return split.incarnations.iterator().next(); // simply peel the only incarnation
				}
				log("Combined all with rank %d into %s", rank, format(split));
				return split;
			}
		}
		return null;
		} finally {
			decLogIndent();
		}
	}
	private static int RANK_VALID = 3;
	private static int rank(PackageBinding candidate) {
		if (candidate == null)
			return 0;
		if (candidate == LookupEnvironment.TheNotFoundPackage)
			return 1;
		if (!candidate.isValidBinding())
			return 2;
		return RANK_VALID;
	}

	public static void incLogIndent() {
		int indent = logIndent.get();
		indent++;
		logIndent.set(indent);
	}
	public static void decLogIndent() {
		int indent = logIndent.get();
		indent--;
		if(indent < 0) {
			indent = 0;
		}
		logIndent.set(indent);
	}
	public static void resetLogIndent() {
		logIndent.set(0);
	}

	public static void log(String format, Object... args) {
		//if(true) return;
		String threadName = format(Thread.currentThread());
		if (threadName.equals("Java indexing") || threadName.equals("JavaReconciler") || threadName.equals("main")) {
			return;
		}
		int indent = logIndent.get();
		StringBuilder sb = new StringBuilder();
		sb.append("[" + threadName + "] ");
		for (int i = 0; i < indent; i++) {
			sb.append("  ");
		}
		if (args == null || args.length == 0) {
			sb.append(format);
		} else {
			sb.append(format.formatted(args));
		}
		System.out.println(sb.toString());
	}

	public static String format(ModuleBinding module) {
		if (module == null) {
			return "<null>";
		}
		return module.getClass().getSimpleName() + "[" + new String(module.moduleName) + "]#" + System.identityHashCode(module);
	}

	public static String format(Thread t) {
		String name = t.getName();
		if (name == null) {
			name = "";
		}
		int index = name.lastIndexOf(".");
		if (index >= 0) {
			name = name.substring(index + 1);
		}
		return name;
	}

	public static String format(PackageBinding binding) {
		StringBuilder sb = new StringBuilder();
		if (binding instanceof PlainPackageBinding) {
			sb.append("plain");
		} else if (binding instanceof SplitPackageBinding) {
			sb.append("split");
		} else if (binding == null) {
			return "<null>";
		} else {
			sb.append("unknown");
		}
		sb.append("#" + System.identityHashCode(binding));
		sb.append(" {" + binding + "}");
		sb.append(" enclosing " + format(binding.enclosingModule));
		return sb.toString();
	}
	public static String format(LookupEnvironment environment) {
		StringBuilder sb = new StringBuilder();
		sb.append(environment.getClass().getSimpleName() + "[" + format(environment.module) + "]#" + System.identityHashCode(environment));
		if (environment.root != environment) {
			sb.append(" -> " + format(environment.root));
		}
		return sb.toString();
	}

	public SplitPackageBinding(PackageBinding initialBinding, ModuleBinding primaryModule) {
		super(initialBinding.compoundName, initialBinding.parent, primaryModule.environment, primaryModule);
		this.declaringModules = new LinkedHashSet<>();
		this.incarnations = new LinkedHashSet<>();
		add(initialBinding);
		// TEST hook:
		if (instanceListener != null) {
			instanceListener.accept(this);
		}
	}
	public void add(PackageBinding packageBinding) {
		if (!supressAddLog) {
			log("Extend split %s with binding %s", format(this), format(packageBinding));
		}
		if (packageBinding instanceof SplitPackageBinding) {
			SplitPackageBinding split = (SplitPackageBinding) packageBinding;
			this.declaringModules.addAll(split.declaringModules);
			for(PlainPackageBinding incarnation: split.incarnations) {
				if(this.incarnations.add(incarnation)) {
					incarnation.addWrappingSplitPackageBinding(this);
				}
			}
		} else if (packageBinding instanceof PlainPackageBinding) {
			this.declaringModules.add(packageBinding.enclosingModule);
			if(this.incarnations.add((PlainPackageBinding) packageBinding)) {
				packageBinding.addWrappingSplitPackageBinding(this);
			}
		}
	}
	@Override
	PackageBinding addPackage(PackageBinding element, ModuleBinding module) {
		if (element.parent != this) {
			SplitPackageBinding.log("Warn! Adding child binding %s with unexpected parent. %s != %s", format(element), format(element.parent), format(this));
		}

		PackageBinding inputBinding = element;
		incLogIndent();
		log("Add package binding %s to %s with module context %s", format(element), format(this), format(module));
		char[] simpleName = element.compoundName[element.compoundName.length-1];
		// enrich
		element = combineWithSiblings(element, simpleName, module);
		log("Enriched sub-binding to %s from %s", format(element), format(inputBinding));

		PackageBinding visible = this.knownPackages.get(simpleName);
		PackageBinding previouslyKnown = visible;
		visible = SplitPackageBinding.combine(element, visible, this.enclosingModule);
		log("Register known package %s replacing %s", format(visible), format(previouslyKnown));
		decLogIndent();
		this.knownPackages.put(simpleName, visible);

		// also record the PPB's as parent-child:
		PlainPackageBinding incarnation = getIncarnation(element.enclosingModule);
		if (incarnation != null) {
			// avoid adding an SPB as a child of a PPB:
			PlainPackageBinding elementIncarnation = element.getIncarnation(element.enclosingModule);
			if (elementIncarnation != null)
				incarnation.addPackage(elementIncarnation, module);
		}
		return element;
	}

	PackageBinding combineWithSiblings(PackageBinding childPackage, char[] name, ModuleBinding module) {
		ModuleBinding primaryModule = childPackage.enclosingModule;
		// see if other incarnations contribute to the child package, too:
		char[] flatName = CharOperation.concatWith(childPackage.compoundName, '.');
		List<PackageBinding> bindings = new ArrayList<>();
		for (PackageBinding incarnation :  this.incarnations) {
			ModuleBinding moduleBinding = incarnation.enclosingModule;
			if (moduleBinding == module)
				continue;
			if (childPackage.isDeclaredIn(moduleBinding))
				continue;
			PlainPackageBinding next = moduleBinding.getDeclaredPackage(flatName);
			if (next != null)
				bindings.add(next);
		}
		if (bindings.isEmpty())
			return childPackage;
		bindings.add(childPackage);
		return combineAll(bindings, primaryModule);
	}

	@Override
	ModuleBinding[] getDeclaringModules() {
		return this.declaringModules.toArray(new ModuleBinding[this.declaringModules.size()]);
	}

	@Override
	PackageBinding getPackage0(char[] name) {
		PackageBinding knownPackage = super.getPackage0(name);
		if (knownPackage != null) {
			if (knownPackage == LookupEnvironment.TheNotFoundPackage) {
				log("Reported package %s not found from cache for %s in getPackage0", new String(name), format(this));
			}
			return knownPackage;
		}

		List<PackageBinding> bindings = new ArrayList<>();
		for (PackageBinding incarnation : this.incarnations) {
			PackageBinding package0 = incarnation.getPackage0(name);
			if (package0 == null)
				return null; // if any incarnation lacks cached info, a full findPackage will be necessary
			bindings.add(package0);
		}
		PackageBinding candidate = combineAll(bindings, this.enclosingModule);
		if (candidate != null){
			log("Register known package %s in parent binding %s", format(candidate), format(this));
			for (PackageBinding incarnation : this.incarnations) {
				PackageBinding package0 = incarnation.getPackage0(name);
				if (package0 instanceof SplitPackageBinding && package0 == candidate) {
					log("ERROR! Adding reference to existing sub-package %s from incarnation %s to package binding %s",
							format(candidate), format(incarnation), format(this));
				}
			}
			this.knownPackages.put(name, candidate);
		}

		return candidate;
	}

	@Override
	PackageBinding getPackage0Any(char[] name) {
		PackageBinding knownPackage = super.getPackage0(name);
		if (knownPackage != null) {
			if (knownPackage == LookupEnvironment.TheNotFoundPackage) {
				log("Reported package %s not found from cache for %s in getPackage0Any", new String(name), format(this));
			}
			return knownPackage;
		}

		List<PackageBinding> bindings = new ArrayList<>();
		for (PackageBinding incarnation : this.incarnations) {
			PackageBinding package0 = incarnation.getPackage0(name);
			if (package0 == null)
				continue;
			bindings.add(package0);
		}
		// don't cache the result, maybe incomplete
		return combineAll(bindings, this.enclosingModule);
	}

	@Override
	protected PackageBinding findPackage(char[] name, ModuleBinding module) {
		if (currentFind != null) {
			log("WARN! Unexpected recursive findPackage with %s and %s", format(currentFind), format(this));
			new Exception().printStackTrace(System.out);
		}
		currentFind = this;

		if (module != this.enclosingModule) {
			log("WARN! My enclosing module %s does not match search context %s", format(this.enclosingModule), format(module));
		}

		log("Find package %s in %s with module context %s", new String(name), format(this), format(module));
		incLogIndent();
		char[][] subpackageCompoundName = CharOperation.arrayConcat(this.compoundName, name);
		Set<PackageBinding> candidates = new LinkedHashSet<>();
		for (ModuleBinding candidateModule : this.declaringModules) {
			PackageBinding candidate = candidateModule.getVisiblePackage(subpackageCompoundName);
			log("Declaring module %s contributed %s", format(candidateModule), format(candidate));
			if (candidate != null
					&& candidate != LookupEnvironment.TheNotFoundPackage
					&& ((candidate.tagBits & TagBits.HasMissingType) == 0))
			{
				candidates.add(candidate);
			}
		}
		int count = candidates.size();
		PackageBinding result = null;
		if (count == 1) {
			result = candidates.iterator().next();
			if(result instanceof SplitPackageBinding) {
				log("ERROR! Leaking non-unique split binding %s", format(result));
			}
		} else if (count > 1) {
			Iterator<PackageBinding> iterator = candidates.iterator();
			SplitPackageBinding split = new SplitPackageBinding(iterator.next(), this.enclosingModule);
			while (iterator.hasNext())
				split.add(iterator.next());
			result = split;
		}
		decLogIndent();
		log("Search result is %s", format(result));
		if (result == null)
			addNotFoundPackage(name);
		else {
			if (result.enclosingModule != this.enclosingModule) {
				log("WARN! Resulting package binding %s has foreign enclosing module. Expected %s", format(result), format(this.enclosingModule));
			}
			if (result instanceof SplitPackageBinding && candidates.contains(result)) {
				log("WARN! Adding non-unique split binding %s as package to %s", format(result), format(this));
			}
			addPackage(result, module);
		}
		currentFind = null;
		return result;
	}

	@Override
	public PlainPackageBinding getIncarnation(ModuleBinding requestedModule) {
		for (PlainPackageBinding incarnation : this.incarnations) {
			if (incarnation.enclosingModule == requestedModule)
				return incarnation;
		}
		return null;
	}

	@Override
	public boolean subsumes(PackageBinding binding) {
		if (!CharOperation.equals(this.compoundName, binding.compoundName))
			return false;
		if (binding instanceof SplitPackageBinding)
			return this.declaringModules.containsAll(((SplitPackageBinding) binding).declaringModules);
		else
			return this.declaringModules.contains(binding.enclosingModule);
	}

	@Override
	boolean hasType0Any(char[] name) {
		if (super.hasType0Any(name))
			return true;

		for (PackageBinding incarnation : this.incarnations) {
			if (incarnation.hasType0Any(name))
				return true;
		}
		return false;
	}

	/** Similar to getType0() but now we have a module and can ask the specific incarnation! */
	ReferenceBinding getType0ForModule(ModuleBinding module, char[] name) {
		if (this.declaringModules.contains(module))
			return getIncarnation(module).getType0(name);
		return null;
	}

	@Override
	ReferenceBinding getType(char[] name, ModuleBinding mod) {
		ReferenceBinding candidate = null;
		boolean accessible = false;
		for (PackageBinding incarnation : this.incarnations) {
			ReferenceBinding type = incarnation.getType(name, mod);
			if (type != null) {
				if (candidate == null || !accessible) {
					candidate = type;
					accessible = mod.canAccess(incarnation);
				} else if (mod.canAccess(incarnation)) {
					return new ProblemReferenceBinding(type.compoundName, candidate, ProblemReasons.Ambiguous); // TODO(SHMOD) add module information
				}
			}
		}
		if (candidate != null && !accessible)
			return new ProblemReferenceBinding(candidate.compoundName, candidate, ProblemReasons.NotAccessible); // TODO(SHMOD) more info
		// at this point we have only checked unique accessibility of the package, accessibility of the type will be checked by callers
		return candidate;
	}

	@Override
	public boolean isDeclaredIn(ModuleBinding moduleBinding) {
		return this.declaringModules.contains(moduleBinding);
	}

	@Override
	public PackageBinding getVisibleFor(ModuleBinding clientModule, boolean preferLocal) {
		int visibleCountInNamedModules = 0;
		PlainPackageBinding uniqueInNamedModules = null;
		PlainPackageBinding bindingInUnnamedModule = null;
		for (PlainPackageBinding incarnation : this.incarnations) {
			if (incarnation.hasCompilationUnit(false)) {
				if (preferLocal && incarnation.enclosingModule == clientModule) {
					return incarnation;
				} else {
					if (clientModule.canAccess(incarnation)) {
						if (incarnation.enclosingModule.isUnnamed()) {
							bindingInUnnamedModule = incarnation;
						} else {
							visibleCountInNamedModules++;
							uniqueInNamedModules = incarnation;
						}
					}
				}
			}
		}
		if (visibleCountInNamedModules > 1) {
			return this; // conflict, return split
		} else if (visibleCountInNamedModules == 1) {
			if (this.environment.globalOptions.ignoreUnnamedModuleForSplitPackage || bindingInUnnamedModule == null) {
				return uniqueInNamedModules;
			} else {
				return this;
			}
		}
		return bindingInUnnamedModule;
	}

	@Override
	public String toString() {
		StringBuilder buf = new StringBuilder(super.toString());
		buf.append(" (from "); //$NON-NLS-1$
		String sep = ""; //$NON-NLS-1$
		for (ModuleBinding mod : this.declaringModules) {
			buf.append(sep).append(mod.readableName());
			sep = ", "; //$NON-NLS-1$
		}
		buf.append(")"); //$NON-NLS-1$
		return buf.toString();
	}
}
