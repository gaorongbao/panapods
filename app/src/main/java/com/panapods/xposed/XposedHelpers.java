package com.panapods.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 传统 Xposed API 工具类（兼容层）
 *
 * 自包含实现，使用 Java 反射，不依赖 Xposed 框架运行时。
 */
public class XposedHelpers {

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, true, classLoader);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        try {
            Method method = findMethodBestMatch(obj.getClass(), methodName, args);
            method.setAccessible(true);
            return method.invoke(obj, args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        try {
            Method method = findMethodBestMatch(clazz, methodName, args);
            method.setAccessible(true);
            return method.invoke(null, args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Object getObjectField(Object obj, String fieldName) {
        try {
            Field field = findField(obj.getClass(), fieldName);
            field.setAccessible(true);
            return field.get(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        try {
            Field field = findField(obj.getClass(), fieldName);
            field.setAccessible(true);
            field.set(obj, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        try {
            Field field = findField(clazz, fieldName);
            field.setAccessible(true);
            return field.get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        try {
            Constructor<?> ctor = findConstructorBestMatch(clazz, args);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Method findMethodBestMatch(Class<?> clazz, String methodName, Object[] args) {
        java.util.List<Method> candidates = new java.util.ArrayList<>();
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == args.length) {
                    candidates.add(m);
                }
            }
        }
        // exact match
        for (Method m : candidates) {
            if (isExactMatch(m.getParameterTypes(), args)) return m;
        }
        // compatible match
        for (Method m : candidates) {
            if (isCompatible(m.getParameterTypes(), args)) return m;
        }
        throw new RuntimeException("Method not found: " + methodName + " on " + clazz.getName());
    }

    private static Constructor<?> findConstructorBestMatch(Class<?> clazz, Object[] args) {
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            if (c.getParameterCount() == args.length && isCompatible(c.getParameterTypes(), args)) {
                return c;
            }
        }
        throw new RuntimeException("Constructor not found on " + clazz.getName());
    }

    private static boolean isExactMatch(Class<?>[] paramTypes, Object[] args) {
        if (paramTypes.length != args.length) return false;
        for (int i = 0; i < paramTypes.length; i++) {
            if (args[i] == null) return false;
            Class<?> argType = args[i].getClass();
            Class<?> paramType = paramTypes[i];
            if (paramType.isPrimitive()) {
                if (boxedClass(paramType) != argType) return false;
            } else {
                if (paramType != argType) return false;
            }
        }
        return true;
    }

    private static boolean isCompatible(Class<?>[] paramTypes, Object[] args) {
        if (paramTypes.length != args.length) return false;
        for (int i = 0; i < paramTypes.length; i++) {
            if (args[i] == null) continue;
            Class<?> paramType = paramTypes[i];
            Class<?> argType = args[i].getClass();
            if (paramType.isPrimitive()) {
                if (boxedClass(paramType) != argType) return false;
            } else {
                if (!paramType.isAssignableFrom(argType)) return false;
            }
        }
        return true;
    }

    private static Class<?> boxedClass(Class<?> primitive) {
        if (primitive == int.class) return Integer.class;
        if (primitive == long.class) return Long.class;
        if (primitive == float.class) return Float.class;
        if (primitive == double.class) return Double.class;
        if (primitive == boolean.class) return Boolean.class;
        if (primitive == byte.class) return Byte.class;
        if (primitive == short.class) return Short.class;
        if (primitive == char.class) return Character.class;
        return primitive;
    }

    private static Field findField(Class<?> clazz, String fieldName) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                // continue
            }
        }
        throw new RuntimeException("Field not found: " + fieldName);
    }
}
