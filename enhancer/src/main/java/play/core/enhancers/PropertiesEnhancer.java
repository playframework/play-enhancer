/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package play.core.enhancers;

import javassist.*;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.SignatureAttribute;
import javassist.bytecode.annotation.Annotation;
import javassist.bytecode.annotation.MemberValue;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * provides property support for Java classes via byte code enhancement.
 */
public class PropertiesEnhancer {

    /**
     * Marks the given method, field or type as one with both a generated setter and getter.
     * PropertiesEnhancer creates this annotation for the enhanced method, field or type.
     */
    @Target({METHOD, FIELD, TYPE})
    @Retention(RUNTIME)
    public static @interface GeneratedAccessor {}

    /**
     * Marks the given method, field or type as one with a generated getter.
     * PropertiesEnhancer creates this annotation for the enhanced method, field or type.
     */
    @Target({METHOD, FIELD, TYPE})
    @Retention(RUNTIME)
    public static @interface GeneratedGetAccessor {}

    /**
     * Marks the given method, field or type as one with a generated setter.
     * PropertiesEnhancer creates this annotation for the enhanced method, field or type.
     */
    @Target({METHOD, FIELD, TYPE})
    @Retention(RUNTIME)
    public static @interface GeneratedSetAccessor {}

    /**
     * Marks the given method, field or type as one with a rewritten setter and getter.
     * PropertiesEnhancer creates this annotation for the enhanced method, field or type.
     */
    @Target({METHOD, FIELD, TYPE})
    @Retention(RUNTIME)
    public static @interface RewrittenAccessor {}

    public static boolean generateAccessors(final String classpath, final File classFile) throws Exception {
        final ClassPool classPool = new ClassPool();
        classPool.appendSystemPath();
        classPool.appendPathList(classpath);

        final FileInputStream is = new FileInputStream(classFile);
        try {
            final CtClass ctClass = classPool.makeClass(is);
            if (hasAnnotation(ctClass, GeneratedAccessor.class)) {
                is.close();
                return false;
            }
            for (final CtField ctField : ctClass.getDeclaredFields()) {
                if (isProperty(ctField)) {

                    final String propertyType = ctField.getType().getSimpleName();
                    final List<String> getters = new ArrayList<String>();
                    final List<String> setters = new ArrayList<String>();

                    if (propertyType.compareTo("boolean") == 0 || propertyType.compareTo("Boolean") == 0) {
                        if (ctField.getName().matches("(?i)(is|has|can)[a-zA-Z0-9\\-_]+")) {
                            getters.add(ctField.getName());
                        } else {
                            final String propertyName = ctField.getName().substring(0, 1).toUpperCase() + ctField.getName().substring(1);
                            getters.add("is" + propertyName);
                        }
                    }
                    // Continue to add default getX and getY for boolean fields to avoid API breaking
                    final String propertyName = ctField.getName().substring(0, 1).toUpperCase() + ctField.getName().substring(1);
                    getters.add("get" + propertyName);
                    setters.add("set" + propertyName);

                    final SignatureAttribute signature = ((SignatureAttribute) ctField.getFieldInfo().getAttribute(SignatureAttribute.tag));

                    for (final String getter : getters) {
                        try {
                            final CtMethod ctMethod = ctClass.getDeclaredMethod(getter);
                            if (ctMethod.getParameterTypes().length > 0 || Modifier.isStatic(ctMethod.getModifiers())) {
                                throw new NotFoundException("it's not a getter !");
                            }
                        } catch (NotFoundException noGetter) {
                            // Create getter
                            final CtMethod getMethod = CtMethod.make("public " + ctField.getType().getName() + " " + getter + "() { return this." + ctField.getName() + "; }", ctClass);
                            ctClass.addMethod(getMethod);
                            createAnnotation(getAnnotations(getMethod), GeneratedAccessor.class);
                            createAnnotation(getAnnotations(ctField), GeneratedGetAccessor.class);
                            if (signature != null) {
                                final String fieldSignature = signature.getSignature();
                                final String getMethodSignature = "()" + fieldSignature;
                                getMethod.getMethodInfo().addAttribute(
                                        new SignatureAttribute(getMethod.getMethodInfo().getConstPool(), getMethodSignature)
                                );
                            }
                        }
                    }

                    for (final String setter : setters) {
                        try {
                            final CtMethod ctMethod = ctClass.getDeclaredMethod(setter, new CtClass[]{ctField.getType()});
                            if (ctMethod.getParameterTypes().length != 1 || !ctMethod.getParameterTypes()[0].equals(ctField.getType()) || Modifier.isStatic(ctMethod.getModifiers())) {
                                throw new NotFoundException("it's not a setter !");
                            }
                        } catch (NotFoundException noSetter) {
                            // Create setter
                            final CtMethod setMethod = CtMethod.make("public void " + setter + "(" + ctField.getType().getName() + " value) { this." + ctField.getName() + " = value; }", ctClass);
                            ctClass.addMethod(setMethod);
                            createAnnotation(getAnnotations(setMethod), GeneratedAccessor.class);
                            createAnnotation(getAnnotations(ctField), GeneratedSetAccessor.class);
                            if (signature != null) {
                                final String fieldSignature = signature.getSignature();
                                final String setMethodSignature = "(" + fieldSignature + ")V";
                                setMethod.getMethodInfo().addAttribute(
                                        new SignatureAttribute(setMethod.getMethodInfo().getConstPool(), setMethodSignature)
                                );
                            }
                        }
                    }
                }
            }

            createAnnotation(getAnnotations(ctClass), GeneratedAccessor.class);

            is.close();
            final FileOutputStream os = new FileOutputStream(classFile);
            os.write(ctClass.toBytecode());
            os.close();
            return true;

        } finally {
            try {
                is.close();
            } catch (Exception ex) {
                // Ignore
            }
        }
    }

    public static boolean rewriteAccess(String classpath, File classFile) throws Exception {
        ClassPool classPool = new ClassPool();
        classPool.appendSystemPath();
        classPool.appendPathList(classpath);

        FileInputStream is = new FileInputStream(classFile);
        try {
            CtClass ctClass = classPool.makeClass(is);
            if(hasAnnotation(ctClass, RewrittenAccessor.class)) {
                is.close();
                return false;
            }

            for (final CtBehavior ctMethod : ctClass.getDeclaredBehaviors()) {
                ctMethod.instrument(new ExprEditor() {

                    @Override
                    public void edit(FieldAccess fieldAccess) throws CannotCompileException {
                        try {

                            // Has accessor
                            if (isAccessor(fieldAccess.getField())) {

                                String propertyName = null;
                                if (fieldAccess.getField().getDeclaringClass().equals(ctMethod.getDeclaringClass())
                                        || ctMethod.getDeclaringClass().subclassOf(fieldAccess.getField().getDeclaringClass())) {
                                    if ((ctMethod.getName().startsWith("get") || ctMethod.getName().startsWith("set")) && ctMethod.getName().length() > 3) {
                                        propertyName = ctMethod.getName().substring(3);
                                        propertyName = propertyName.substring(0, 1).toLowerCase() + propertyName.substring(1);
                                    }
                                }

                                if (propertyName == null || !propertyName.equals(fieldAccess.getFieldName())) {

                                    String getSet = fieldAccess.getFieldName().substring(0,1).toUpperCase() + fieldAccess.getFieldName().substring(1);

                                    if (fieldAccess.isReader() && hasAnnotation(fieldAccess.getField(), GeneratedGetAccessor.class)) {
                                        // Rewrite read access
                                        fieldAccess.replace("$_ = $0.get" + getSet + "();");
                                    } else if (fieldAccess.isWriter() && hasAnnotation(fieldAccess.getField(), GeneratedSetAccessor.class)) {
                                        // Rewrite write access
                                        fieldAccess.replace("$0.set" + getSet + "($1);");
                                    }
                                }
                            }

                        } catch (Exception e) {
                            // Not sure why this is being ignored... but presumably there is a reason
                        }
                    }
                });
            }

            createAnnotation(getAnnotations(ctClass), RewrittenAccessor.class);

            is.close();
            FileOutputStream os = new FileOutputStream(classFile);
            os.write(ctClass.toBytecode());
            os.close();

        } finally {
            try {
                is.close();
            } catch(Exception ex) {
                // Ignore
            }
        }
        return true;
    }

    // --

    static boolean isProperty(CtField ctField) {
        if (ctField.getName().equals(ctField.getName().toUpperCase()) || ctField.getName().substring(0, 1).equals(ctField.getName().substring(0, 1).toUpperCase())) {
            return false;
        }
        return Modifier.isPublic(ctField.getModifiers())
                && !Modifier.isFinal(ctField.getModifiers())
                && !Modifier.isStatic(ctField.getModifiers());
    }

    static boolean isAccessor(CtField ctField) throws Exception {
        return hasAnnotation(ctField, GeneratedGetAccessor.class) || hasAnnotation(ctField, GeneratedSetAccessor.class);
    }

    // --

    /**
     * Test if a class has the provided annotation
     */
    static boolean hasAnnotation(CtClass ctClass, Class<? extends java.lang.annotation.Annotation> annotationType) throws ClassNotFoundException {
        return getAnnotations(ctClass).getAnnotation(annotationType.getName()) != null;
    }

    /**
     * Test if a field has the provided annotation
     */
    static boolean hasAnnotation(CtField ctField, Class<? extends java.lang.annotation.Annotation> annotationType) throws ClassNotFoundException {
        return getAnnotations(ctField).getAnnotation(annotationType.getName()) != null;
    }

    /**
     * Retrieve all class annotations.
     */
    static AnnotationsAttribute getAnnotations(CtClass ctClass) {
        AnnotationsAttribute annotationsAttribute = (AnnotationsAttribute) ctClass.getClassFile().getAttribute(AnnotationsAttribute.visibleTag);
        if (annotationsAttribute == null) {
            annotationsAttribute = new AnnotationsAttribute(ctClass.getClassFile().getConstPool(), AnnotationsAttribute.visibleTag);
            ctClass.getClassFile().addAttribute(annotationsAttribute);
        }
        return annotationsAttribute;
    }

    /**
     * Retrieve all field annotations.
     */
    static AnnotationsAttribute getAnnotations(CtField ctField) {
        AnnotationsAttribute annotationsAttribute = (AnnotationsAttribute) ctField.getFieldInfo().getAttribute(AnnotationsAttribute.visibleTag);
        if (annotationsAttribute == null) {
            annotationsAttribute = new AnnotationsAttribute(ctField.getFieldInfo().getConstPool(), AnnotationsAttribute.visibleTag);
            ctField.getFieldInfo().addAttribute(annotationsAttribute);
        }
        return annotationsAttribute;
    }

    /**
     * Retrieve all method annotations.
     */
    static AnnotationsAttribute getAnnotations(CtMethod ctMethod) {
        AnnotationsAttribute annotationsAttribute = (AnnotationsAttribute) ctMethod.getMethodInfo().getAttribute(AnnotationsAttribute.visibleTag);
        if (annotationsAttribute == null) {
            annotationsAttribute = new AnnotationsAttribute(ctMethod.getMethodInfo().getConstPool(), AnnotationsAttribute.visibleTag);
            ctMethod.getMethodInfo().addAttribute(annotationsAttribute);
        }
        return annotationsAttribute;
    }

    /**
     * Create a new annotation to be dynamically inserted in the byte code.
     */
    static void createAnnotation(AnnotationsAttribute attribute, Class<? extends java.lang.annotation.Annotation> annotationType, Map<String, MemberValue> members) {
        Annotation annotation = new Annotation(annotationType.getName(), attribute.getConstPool());
        for (Map.Entry<String, MemberValue> member : members.entrySet()) {
            annotation.addMemberValue(member.getKey(), member.getValue());
        }
        attribute.addAnnotation(annotation);
    }

    /**
     * Create a new annotation to be dynamically inserted in the byte code.
     */
    static void createAnnotation(AnnotationsAttribute attribute, Class<? extends java.lang.annotation.Annotation> annotationType) {
        createAnnotation(attribute, annotationType, new HashMap<String, MemberValue>());
    }
}
