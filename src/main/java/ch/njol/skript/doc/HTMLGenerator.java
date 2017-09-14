/**
 *   This file is part of Skript.
 *
 *  Skript is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Skript is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with Skript.  If not, see <http://www.gnu.org/licenses/>.
 *
 *
 * Copyright 2011-2017 Peter Güttinger and contributors
 */
package ch.njol.skript.doc;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Matcher;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

import ch.njol.skript.Skript;
import ch.njol.skript.classes.ClassInfo;
import ch.njol.skript.lang.Condition;
import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.ExpressionInfo;
import ch.njol.skript.lang.SkriptEventInfo;
import ch.njol.skript.lang.SyntaxElementInfo;
import ch.njol.skript.lang.function.Functions;
import ch.njol.skript.lang.function.JavaFunction;
import ch.njol.skript.lang.function.Parameter;
import ch.njol.skript.registrations.Classes;
import ch.njol.skript.util.Utils;
import ch.njol.util.Callback;
import ch.njol.util.NonNullPair;
import ch.njol.util.StringUtils;

/**
 * Generates HTML based Skript documentation.
 */
public class HTMLGenerator {
	
	private File template;
	private File output;
	
	private String skeleton;
	
	public HTMLGenerator(File templateDir, File outputDir) {
		this.template = templateDir;
		this.output = outputDir;
		
		this.skeleton = readFile(new File(template + "/template.html")); // Skeleton which contains every other page
	}
	
	public void generate() {
		for (File f : template.listFiles()) {			
			if (f.getName().equals("css")) { // Copy CSS files
				File cssTo = new File(output + "/css");
				cssTo.mkdirs();
				for (File css : new File(template + "/css").listFiles()) {
					writeFile(new File(cssTo + "/" + css.getName()), readFile(css));
				}
				continue;
			} else if (f.isDirectory()) // Ignore other directories
				continue;
			if (f.getName().endsWith("template.html") || f.getName().endsWith(".md"))
				continue; // Ignore skeleton and README
			Skript.info("Creating documentation for " + f.getName());
			
			String content = readFile(f);
			String page;
			if (f.getName().endsWith(".html"))
				page = skeleton.replace("${content}", content); // Content to inside skeleton
			else // Not HTML, so don't even try to use template.html
				page = content;
			
			page = page.replace("${skript.version}", Skript.getVersion().toString()); // Skript version
			page = page.replace("${pagename}", f.getName().replace(".html", ""));
			
			List<String> replace = Lists.newArrayList();
			int include = page.indexOf("${include"); // Single file includes
			while (include != -1) {
				int endIncl = page.indexOf("}", include);
				String name = page.substring(include + 10, endIncl);
				replace.add(name);
				
				include = page.indexOf("${include", endIncl);
			}
			
			for (String name : replace) {
				String temp = readFile(new File(template + "/templates/" + name));
				page = page.replace("${include " + name + "}", temp);
			}
			
			int generate = page.indexOf("${generate"); // Generate expressions etc.
			while (generate != -1) {
				int nextBracket = page.indexOf("}", generate);
				String[] genParams = page.substring(generate + 11, nextBracket).split(" ");
				String generated = "";
				
				String descTemp = readFile(new File(template + "/templates/" + genParams[1]));
				String genType = genParams[0];
				if (genType.equals("expressions")) {
					Iterator<ExpressionInfo<?,?>> it = Skript.getExpressions();
					while (it.hasNext()) {
						ExpressionInfo<?,?> info = it.next();
						assert info != null;
						if (info.c.getAnnotation(NoDoc.class) != null)
							continue;
						String desc = generateAnnotated(descTemp, info);
						generated += desc;
					}
				} else if (genType.equals("effects")) {
					for (SyntaxElementInfo<? extends Effect> info : Skript.getEffects()) {
						assert info != null;
						if (info.c.getAnnotation(NoDoc.class) != null)
							continue;
						generated += generateAnnotated(descTemp, info);
					}
				} else if (genType.equals("conditions")) {
					for (SyntaxElementInfo<? extends Condition> info : Skript.getConditions()) {
						assert info != null;
						if (info.c.getAnnotation(NoDoc.class) != null)
							continue;
						generated += generateAnnotated(descTemp, info);
					}
				} else if (genType.equals("events")) {
					for (SkriptEventInfo<?> info : Skript.getEvents()) {
						assert info != null;
						if (info.c.getAnnotation(NoDoc.class) != null)
							continue;
						generated += generateEvent(descTemp, info);
					}
				} else if (genType.equals("classes")) {
					for (ClassInfo<?> info : Classes.getClassInfos()) {
						if (ClassInfo.NO_DOC.equals(info.getDocName()))
							continue;
						assert info != null;
						generated += generateClass(descTemp, info);
					}
				} else if (genType.equals("functions")) {
					Iterable<JavaFunction<?>> functions = Functions.getJavaFunctions();
					for (JavaFunction<?> info : functions) {
						assert info != null;
						generated += generateFunction(descTemp, info);
					}
				}
				
				page = page.replace(page.substring(generate, nextBracket + 1), generated);
				
				generate = page.indexOf("${generate", nextBracket);
			}
			
			writeFile(new File(output + "/" + f.getName()), page);
		}
	}
	
	/**
	 * Generates documentation entry for a type which is documented using
	 * annotations. This means expressions, effects and conditions.
	 * @param descTemp Template for description.
	 * @param info Syntax element info.
	 * @return Generated HTML entry.
	 */
	String generateAnnotated(String descTemp, SyntaxElementInfo<?> info) {
		Class<?> c = info.c;
		String desc = "";
		
		Name name = c.getAnnotation(Name.class);
		desc = descTemp.replace("${element.name}", name == null ? "Unknown Name" : name.value());
		Since since = c.getAnnotation(Since.class);
		desc = desc.replace("${element.since}", since == null ? "unknown" : since.value());
		Description description = c.getAnnotation(Description.class);
		desc = desc.replace("${element.desc}", Joiner.on("\n").join(description == null ? new String[0] : description.value()));
		desc = desc.replace("${element.desc-safe}", Joiner.on("\\n").join(description == null ? new String[0] : description.value())
				.replace("\\", "\\\\").replace("\"", "\\\"").replace("\t", "    "));
		Examples examples = c.getAnnotation(Examples.class);
		desc = desc.replace("${element.examples}", Joiner.on("\n<br>").join(examples == null ? new String[0] : examples.value()));
		desc = desc.replace("${element.examples-safe}", Joiner.on("\\n").join(examples == null ? new String[0] : examples.value())
				.replace("\\", "\\\\").replace("\"", "\\\"").replace("\t", "    "));
		desc = desc.replace("${element.id}", info.c.getSimpleName());
		
		List<String> toGen = Lists.newArrayList();
		int generate = desc.indexOf("${generate");
		while (generate != -1) {
			//Skript.info("Found generate!");
			int nextBracket = desc.indexOf("}", generate);
			String data = desc.substring(generate + 11, nextBracket);
			toGen.add(data);
			//Skript.info("Added " + data);
			
			generate = desc.indexOf("${generate", nextBracket);
		}
		
		// Assume element.pattern generate
		for (String data : toGen) {
			String[] split = data.split(" ");
			String pattern = readFile(new File(template + "/templates/" + split[1]));
			//Skript.info("Pattern is " + pattern);
			String patterns = "";
			for (String line : info.patterns) {
				assert line != null;
				line = cleanPatterns(line);
				String parsed = pattern.replace("${element.pattern}", line);
				//Skript.info("parsed is " + parsed);
				patterns += parsed;
			}
			
			String toReplace = "${generate element.patterns " + split[1] + "}";
			//Skript.info("toReplace " + toReplace);
			desc = desc.replace(toReplace, patterns);
			desc = desc.replace("${generate element.patterns-safe " + split[1] + "}", patterns.replace("\\", "\\\\"));
		}
		
		assert desc != null;
		return desc;
	}
	
	String generateEvent(String descTemp, SkriptEventInfo<?> info) {
		String desc = "";
		
		String docName = info.getName();
		desc = descTemp.replace("${element.name}", docName);
		String since = info.getSince();
		desc = desc.replace("${element.since}", since == null ? "unknown" : since);
		String[] description = info.getDescription();
		desc = desc.replace("${element.desc}", Joiner.on("\n").join(description == null ? new String[0] : description));
		desc = desc.replace("${element.desc-safe}", Joiner.on("\\n").join(description == null ? new String[0] : description)
				.replace("\\", "\\\\").replace("\"", "\\\"").replace("\t", "    "));
		String[] examples = info.getExamples();
		desc = desc.replace("${element.examples}", Joiner.on("\n<br>").join(examples == null ? new String[0] : examples));
		desc = desc.replace("${element.examples-safe}", Joiner.on("\\n").join(examples == null ? new String[0] : examples)
				.replace("\\", "\\\\").replace("\"", "\\\"").replace("\t", "    "));
		desc = desc.replace("${element.id}", info.getId());
		
		List<String> toGen = Lists.newArrayList();
		int generate = desc.indexOf("${generate");
		while (generate != -1) {
			int nextBracket = desc.indexOf("}", generate);
			String data = desc.substring(generate + 11, nextBracket);
			toGen.add(data);
			
			generate = desc.indexOf("${generate", nextBracket);
		}
		
		// Assume element.pattern generate
		for (String data : toGen) {
			String[] split = data.split(" ");
			String pattern = readFile(new File(template + "/templates/" + split[1]));
			String patterns = "";
			for (String line : info.patterns) {
				assert line != null;
				line = cleanPatterns(line);
				String parsed = pattern.replace("${element.pattern}", line);
				patterns += parsed;
			}
			
			desc = desc.replace("${generate element.patterns " + split[1] + "}", patterns);
			desc = desc.replace("${generate element.patterns-safe " + split[1] + "}", patterns.replace("\\", "\\\\"));
		}
		
		assert desc != null;
		return desc;
	}
	
	String generateClass(String descTemp, ClassInfo<?> info) {
		String desc = "";
		
		String docName = info.getDocName();
		desc = descTemp.replace("${element.name}", docName == null ? "Unknown Name" : docName);
		String since = info.getSince();
		desc = desc.replace("${element.since}", since == null ? "unknown" : since);
		String[] description = info.getDescription();
		desc = desc.replace("${element.desc}", Joiner.on("\n").join(description == null ? new String[0] : description));
		desc = desc.replace("${element.desc-safe}", Joiner.on("\\n").join(description == null ? new String[0] : description)
				.replace("\\", "\\\\").replace("\"", "\\\"").replace("\t", "    "));
		String[] examples = info.getExamples();
		desc = desc.replace("${element.examples}", Joiner.on("\n<br>").join(examples == null ? new String[0] : examples));
		desc = desc.replace("${element.examples-safe}", Joiner.on("\\n").join(examples == null ? new String[0] : examples)
				.replace("\\", "\\\\").replace("\"", "\\\"").replace("\t", "    "));
		desc = desc.replace("${element.id}", info.getCodeName());
		
		List<String> toGen = Lists.newArrayList();
		int generate = desc.indexOf("${generate");
		while (generate != -1) {
			int nextBracket = desc.indexOf("}", generate);
			String data = desc.substring(generate + 11, nextBracket);
			toGen.add(data);
			
			generate = desc.indexOf("${generate", nextBracket);
		}
		
		// Assume element.pattern generate
		for (String data : toGen) {
			String[] split = data.split(" ");
			String pattern = readFile(new File(template + "/templates/" + split[1]));
			String patterns = "";
			String[] lines = info.getUsage();
			if (lines == null)
				continue;
			for (String line : lines) {
				assert line != null;
				line = cleanPatterns(line);
				String parsed = pattern.replace("${element.pattern}", line);
				patterns += parsed;
			}
			
			desc = desc.replace("${generate element.patterns " + split[1] + "}", patterns);
			desc = desc.replace("${generate element.patterns-safe " + split[1] + "}", patterns.replace("\\", "\\\\"));
		}
		
		assert desc != null;
		return desc;
	}
	
	String generateFunction(String descTemp, JavaFunction<?> info) {
		String desc = "";
		
		String docName = info.getName();
		desc = descTemp.replace("${element.name}", docName);
		String since = info.getSince();
		desc = desc.replace("${element.since}", since == null ? "unknown" : since);
		String[] description = info.getDescription();
		desc = desc.replace("${element.desc}", Joiner.on("\n").join(description == null ? new String[0] : description));
		desc = desc.replace("${element.desc-safe}", Joiner.on("\\n").join(description == null ? new String[0] : description)
				.replace("\\", "\\\\").replace("\"", "\\\"").replace("\t", "    "));
		String[] examples = info.getExamples();
		desc = desc.replace("${element.examples}", Joiner.on("\n<br>").join(examples == null ? new String[0] : examples));
		desc = desc.replace("${element.examples-safe}", Joiner.on("\\n").join(examples == null ? new String[0] : examples)
				.replace("\\", "\\\\").replace("\"", "\\\"").replace("\t", "    "));
		desc = desc.replace("${element.id}", info.getName());
		
		List<String> toGen = Lists.newArrayList();
		int generate = desc.indexOf("${generate");
		while (generate != -1) {
			int nextBracket = desc.indexOf("}", generate);
			String data = desc.substring(generate + 11, nextBracket);
			toGen.add(data);
			
			generate = desc.indexOf("${generate", nextBracket);
		}
		
		// Assume element.pattern generate
		for (String data : toGen) {
			String[] split = data.split(" ");
			String pattern = readFile(new File(template + "/templates/" + split[1]));
			String patterns = "";
			Parameter<?>[] params = info.getParameters();
			String[] types = new String[params.length];
			for (int i = 0; i < types.length; i++) {
				types[i] = params[i].getType().getDocName();
			}
			String line = docName + "(" + Joiner.on(", ").join(types) + ")";
			patterns += pattern.replace("${element.pattern}", line);
			
			desc = desc.replace("${generate element.patterns " + split[1] + "}", patterns);
			desc = desc.replace("${generate element.patterns-safe " + split[1] + "}", patterns.replace("\\", "\\\\"));
		}
		
		assert desc != null;
		return desc;
	}
	
	@SuppressWarnings("null")
	String readFile(File f) {
		try {
			return Files.toString(f, Charset.forName("UTF-8"));
		} catch (IOException e) {
			e.printStackTrace();
			return "";
		}
	}
	
	void writeFile(File f, String data) {
		try {
			Files.write(data, f, Charset.forName("UTF-8"));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	final static String cleanPatterns(final String patterns) {
		final String s = StringUtils.replaceAll("" +
				Documentation.escapeHTML(patterns) // escape HTML
				.replaceAll("(?<=[\\(\\|])[-0-9]+?¦", "") // remove marks
				.replace("()", "") // remove empty mark setting groups (mark¦)
				.replaceAll("\\(([^|]+?)\\|\\)", "[$1]") // replace (mark¦x|) groups with [x]
				.replaceAll("\\(\\|([^|]+?)\\)", "[$1]") // dito
				.replaceAll("\\((.+?)\\|\\)", "[($1)]") // replace (a|b|) with [(a|b)]
				.replaceAll("\\(\\|(.+?)\\)", "[($1)]") // dito
		, "(?<!\\\\)%(.+?)(?<!\\\\)%", new Callback<String, Matcher>() { // link & fancy types
			@Override
			public String run(final Matcher m) {
				String s = m.group(1);
				if (s.startsWith("-"))
					s = s.substring(1);
				String flag = "";
				if (s.startsWith("*") || s.startsWith("~")) {
					flag = s.substring(0, 1);
					s = s.substring(1);
				}
				final int a = s.indexOf("@");
				if (a != -1)
					s = s.substring(0, a);
				final StringBuilder b = new StringBuilder("%");
				b.append(flag);
				boolean first = true;
				for (final String c : s.split("/")) {
					assert c != null;
					if (!first)
						b.append("/");
					first = false;
					final NonNullPair<String, Boolean> p = Utils.getEnglishPlural(c);
					final ClassInfo<?> ci = Classes.getClassInfoNoError(p.getFirst());
					if (ci != null && ci.getDocName() != null && ci.getDocName() != ClassInfo.NO_DOC) {
						b.append("<a href='classes.html#").append(p.getFirst()).append("'>").append(ci.getName().toString(p.getSecond())).append("</a>");
					} else {
						b.append(c);
						if (ci != null && ci.getDocName() != ClassInfo.NO_DOC)
							Skript.warning("Used class " + p.getFirst() + " has no docName/name defined");
					}
				}
				return "" + b.append("%").toString();
			}
		});
		assert s != null : patterns;
		return s;
	}
	
}
