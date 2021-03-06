/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.common.bytecode;

import com.alibaba.dubbo.common.utils.ClassHelper;
import com.alibaba.dubbo.common.utils.ReflectUtils;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;

/**
 * Wrapper,基于javassit库动态编译。
 * 1 用于创建某个对象的方法调用的包装器，以避免反射调用，提高性能
 */
public abstract class Wrapper {
    /**
     * Wrapper缓存对象
     * key: Wrapper 包装的类； value: Wrapper对象
     */
    private static final Map<Class<?>, Wrapper> WRAPPER_MAP = new ConcurrentHashMap<Class<?>, Wrapper>(); //class wrapper map
    private static final String[] EMPTY_STRING_ARRAY = new String[0];
    private static final String[] OBJECT_METHODS = new String[]{"getClass", "hashCode", "toString", "equals"};
    /**
     * 创建匿名对象
     */
    private static final Wrapper OBJECT_WRAPPER = new Wrapper() {
        @Override
        public String[] getMethodNames() {
            return OBJECT_METHODS;
        }

        @Override
        public String[] getDeclaredMethodNames() {
            return OBJECT_METHODS;
        }

        @Override
        public String[] getPropertyNames() {
            return EMPTY_STRING_ARRAY;
        }

        @Override
        public Class<?> getPropertyType(String pn) {
            return null;
        }

        @Override
        public Object getPropertyValue(Object instance, String pn) throws NoSuchPropertyException {
            throw new NoSuchPropertyException("Property [" + pn + "] not found.");
        }

        @Override
        public void setPropertyValue(Object instance, String pn, Object pv) throws NoSuchPropertyException {
            throw new NoSuchPropertyException("Property [" + pn + "] not found.");
        }

        @Override
        public boolean hasProperty(String name) {
            return false;
        }

        @Override
        public Object invokeMethod(Object instance, String mn, Class<?>[] types, Object[] args) throws NoSuchMethodException {
            if ("getClass".equals(mn)) {
                return instance.getClass();
            }
            if ("hashCode".equals(mn)) {
                return instance.hashCode();
            }
            if ("toString".equals(mn)) {
                return instance.toString();
            }
            if ("equals".equals(mn)) {
                if (args.length == 1) {
                    return instance.equals(args[0]);
                }
                throw new IllegalArgumentException("Invoke method [" + mn + "] argument number error.");
            }
            throw new NoSuchMethodException("Method [" + mn + "] not found.");
        }
    };
    private static AtomicLong WRAPPER_CLASS_COUNTER = new AtomicLong(0);

    /**
     * Wrapper 用于“包裹”目标类，Wrapper 是一个抽象类，仅可通过 getWrapper(Class) 方法创建子类。在创建 Wrapper 子类的过程中，
     * 子类代码生成逻辑会对 getWrapper 方法传入的 Class 对象进行解析，拿到诸如类方法，类成员变量等信息，以及生成 invokeMethod 方法代码
     * 和其他一些方法代码。代码生成完毕后，通过 Javassist 生成 Class 对象，最后再通过反射创建 Wrapper 实例。
     * 关键一点：
     * 实现的invokeMethod方法封装了传入的Class的方法
     *
     * @param c Class instance.
     * @return Wrapper instance(not null).
     */
    public static Wrapper getWrapper(Class<?> c) {
        // can not wrapper on dynamic class.
        /**
         * 1 ClassGenerator是类生成器，基于Javassist实现。
         * 2 判断是否继承ClassGenerator.DC.class，如果是就拿到父类，避免重复包装
         */
        while (ClassGenerator.isDynamicClass(c)) {
            c = c.getSuperclass();
        }

        // 如果是Object.class就直接返回创建的匿名Wrapper
        if (c == Object.class) {
            return OBJECT_WRAPPER;
        }
        // 从缓存中获得Wrapper
        Wrapper ret = WRAPPER_MAP.get(c);
        if (ret == null) {
            // 缓存未命中，创建Wrapper
            ret = makeWrapper(c);
            // 写入缓存
            WRAPPER_MAP.put(c, ret);
        }
        return ret;
    }

    /**
     * 创建Wrapper
     *
     * @param c
     * @return
     */
    private static Wrapper makeWrapper(Class<?> c) {
        // 私有类不支持创建，抛出异常
        if (c.isPrimitive()) {
            throw new IllegalArgumentException("Can not create wrapper for primitive type: " + c);
        }

        // 类名
        String name = c.getName();
        // 类加载器
        ClassLoader cl = ClassHelper.getClassLoader(c);

        /**
         * c1 用于存储 setPropertyValue 方法代码
         */
        StringBuilder c1 = new StringBuilder("public void setPropertyValue(Object o, String n, Object v){ ");
        /**
         * c2 用于存储getPropertyValue 方法代码
         */
        StringBuilder c2 = new StringBuilder("public Object getPropertyValue(Object o, String n){ ");
        /**
         * c3 用于存储 invokeMethod 方法代码
         */
        StringBuilder c3 = new StringBuilder("public Object invokeMethod(Object o, String n, Class[] p, Object[] v) throws " + InvocationTargetException.class.getName() + "{ ");

        /**
         * 添加每个方法的被调用对象的类型转换的代码。即生成类型转换代码及异常捕捉代码，如：
         */
        c1.append(name).append(" w; try{ w = ((").append(name).append(")$1); }catch(Throwable e){ throw new IllegalArgumentException(e); }");
        c2.append(name).append(" w; try{ w = ((").append(name).append(")$1); }catch(Throwable e){ throw new IllegalArgumentException(e); }");
        c3.append(name).append(" w; try{ w = ((").append(name).append(")$1); }catch(Throwable e){ throw new IllegalArgumentException(e); }");

        /**
         * pts 用于存储成员变量名和类型
         */
        Map<String, Class<?>> pts = new HashMap<String, Class<?>>(); // <property name, property types>
        /**
         * ms 用于存储方法描述信息(可理解为方法签名) 及 Method 实例
         */
        Map<String, Method> ms = new LinkedHashMap<String, Method>(); // <method desc, Method instance>
        /**
         * mns 为方法名列表
         */
        List<String> mns = new ArrayList<String>(); // method names.
        /**
         * dmns 用于存储定义在当前类中的方法的名称
         */
        List<String> dmns = new ArrayList<String>(); // declaring method names.


        // -------------------------------------------------- 分割线1 ----------------------------------------------------

        // 获取public 访问级别的字段，并为所有字段生成条件判断语句
        for (Field f : c.getFields()) {
            String fn = f.getName();
            Class<?> ft = f.getType();
            // 忽略关键字 static 或 transient 修饰的变量
            if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) {
                continue;
            }

            // 生成条件判断及赋值语句，如：if($2.equals("name")){w.name = (java.lang.String)$3;return;}
            c1.append(" if( $2.equals(\"").append(fn).append("\") ){ w.").append(fn).append("=").append(arg(ft, "$3")).append("; return; }");

            // 生成条件判断及返回语句，如: if($2.equals("name")){return ($w)w.name;}
            c2.append(" if( $2.equals(\"").append(fn).append("\") ){ return ($w)w.").append(fn).append("; }");

            // 存储 <字段名，字段类型> 赋值对到pts中
            pts.put(fn, ft);
        }


        // -------------------------------------------------- 分割线2 ---------------------------------------------------

        // 获取c中的方法列表
        Method[] methods = c.getMethods();
        // get all public method. // 检测c中是否包含在当前类中声明的方法
        boolean hasMethod = hasMethods(methods);
        if (hasMethod) {
            c3.append(" try{");
        }

        for (Method m : methods) {
            // 忽略Ojbect 中定义的方法
            if (m.getDeclaringClass() == Object.class) //ignore Object's method.
            {
                continue;
            }

            String mn = m.getName();
            // 生成方法名判断语句，如：if("sleep".equals($2))，这里使用方法名+ 方法参数长度来判断
            c3.append(" if( \"").append(mn).append("\".equals( $2 ) ");
            int len = m.getParameterTypes().length;
            // 生成运行时传入参数的数量与方法的参数列表长度判读语句，如：&& $3.length == 2
            c3.append(" && ").append(" $3.length == ").append(len);

            // 若相同方法名存在多个，增加参数类型数组的比较判断
            boolean override = false;
            for (Method m2 : methods) {
                // 检测方法是否存在重载情况，条件为：方法对象不同 && 方法名相同
                if (m != m2 && m.getName().equals(m2.getName())) {
                    override = true;
                    break;
                }
            }
            /**
             * 对重载方法进行处理，考虑下面的方法：
             * 1 void sayHello(Integer,String)
             * 2 void sayHello(Integer,Integer)
             * 方法名相同，参数列表长度也相同，因此不能仅通过上面两个条件判断两个方法是否相等，需要进一步判断方法的参数类型
             */
            if (override) {
                if (len > 0) {
                    for (int l = 0; l < len; l++) {
                        /**
                         * && $3[0].getName().equals("java.lang.Integer") && $3[1].getName().equals("java.lang.String")
                         */
                        c3.append(" && ").append(" $3[").append(l).append("].getName().equals(\"")
                                .append(m.getParameterTypes()[l].getName()).append("\")");
                    }
                }
            }

            /**
             * 添加 ){,完成方法判断语句，此时生成的方法可能如下：
             * if("sayHello".equals($2) && $3.length == 2 && $3[0].getName().equals("java.lang.Integer") && $3[1].getName().equals("java.lang.String")
             */
            c3.append(" ) { ");

            // 根据返回值类型生成目标方法调用语句
            if (m.getReturnType() == Void.TYPE) {
                // w.sayHello((java.lang.Integer)$4[0],(java.lang.String)$4[1]); return null;
                c3.append(" w.").append(mn).append('(').append(args(m.getParameterTypes(), "$4")).append(");").append(" return null;");
            } else {
                // return w.sayHello((java.lang.Integer)$4[0],(java.lang.String)$4[1])
                c3.append(" return ($w)w.").append(mn).append('(').append(args(m.getParameterTypes(), "$4")).append(");");
            }

            /**
             * 添加 } ，当前方法判断条件代码生成完毕，可能如下：
             *
             * if("sayHello".equals($2) && $3.length == 2 && $3[0].getName().equals("java.lang.Integer") && $3[1].getName().equals("java.lang.String")
             *     w.sayHello((java.lang.Integer)$4[0],(java.lang.String)$4[1]); return null;
             *     return null;
             *     }
             */
            c3.append(" }");

            // 添加方法名到mns集合中
            mns.add(mn);
            // 检测当前方法是否在c中被声明
            if (m.getDeclaringClass() == c) {
                // 若是，则将当前方法名添加到dmns中
                dmns.add(mn);
            }
            ms.put(ReflectUtils.getDesc(m), m);
        }
        // 如果有方法，就添加 invokeMethod(o,n,p,v) 的 catch 的代码
        if (hasMethod) {
            // 添加异常捕获语句
            c3.append(" } catch(Throwable e) { ");
            c3.append("     throw new java.lang.reflect.InvocationTargetException(e); ");
            c3.append(" }");
        }

        // 添加 invokeMethod(o,n,p,v) 的未匹配到方法的代码。即添加NoSuchMethodException 异常抛出代码
        c3.append(" throw new " + NoSuchMethodException.class.getName() + "(\"Not found method \\\"\"+$2+\"\\\" in class " + c.getName() + ".\"); }");

        //----------------------------------------------分割线3----------------------------------------------------------

        // 循环 setting/getting 方法，添加每个属性的设置和获得分别到 `#setPropertyValue(o, n, v)` 和 `#getPropertyValue(o, n)` 的代码
        // deal with get/set method.
        Matcher matcher;
        // 处理get/set方法
        for (Map.Entry<String, Method> entry : ms.entrySet()) {
            String md = entry.getKey();
            Method method = (Method) entry.getValue();
            // 匹配以get开头的方法
            if ((matcher = ReflectUtils.GETTER_METHOD_DESC_PATTERN.matcher(md)).matches()) {
                // 获取属性名
                String pn = propertyName(matcher.group(1));
                // 生成属性判断以及返回语句，示例如： if($2.equals("name")) { return ($w).w.getName()}
                c2.append(" if( $2.equals(\"").append(pn).append("\") ){ return ($w)w.").append(method.getName()).append("(); }");
                pts.put(pn, method.getReturnType());
                // 匹配以is/has/can 开头的方法
            } else if ((matcher = ReflectUtils.IS_HAS_CAN_METHOD_DESC_PATTERN.matcher(md)).matches()) {
                String pn = propertyName(matcher.group(1));
                // 生成属性判断以及 返回语句，如： if($2.equals("dream")){return ($w)w.hasDream();}
                c2.append(" if( $2.equals(\"").append(pn).append("\") ){ return ($w)w.").append(method.getName()).append("(); }");
                pts.put(pn, method.getReturnType());
                // 匹配以set 开头的方法
            } else if ((matcher = ReflectUtils.SETTER_METHOD_DESC_PATTERN.matcher(md)).matches()) {
                Class<?> pt = method.getParameterTypes()[0];
                String pn = propertyName(matcher.group(1));
                // 生成属性判断以及setter 调用语句，如： if($2.equals("name")){w.setName(java.lang.String)$3);return;}
                c1.append(" if( $2.equals(\"").append(pn).append("\") ){ w.").append(method.getName()).append("(").append(arg(pt, "$3")).append("); return; }");
                pts.put(pn, pt);
            }
        }
        // 添加 NoSuchPropertyException 异常抛出语句
        c1.append(" throw new " + NoSuchPropertyException.class.getName() + "(\"Not found property \\\"\"+$2+\"\\\" filed or setter method in class " + c.getName() + ".\"); }");
        c2.append(" throw new " + NoSuchPropertyException.class.getName() + "(\"Not found property \\\"\"+$2+\"\\\" filed or setter method in class " + c.getName() + ".\"); }");

        //--------------------------------------------------- 分割线4 ---------------------------------------------------

        // make class
        long id = WRAPPER_CLASS_COUNTER.getAndIncrement();
        // 创建类生成器
        ClassGenerator cc = ClassGenerator.newInstance(cl);
        // 设置类名
        cc.setClassName((Modifier.isPublic(c.getModifiers()) ? Wrapper.class.getName() : c.getName() + "$sw") + id);
        // 设置父类为 Wrapper.class
        cc.setSuperClass(Wrapper.class);

        // 添加默认构造方法
        cc.addDefaultConstructor();
        // 添加静态属性 `pns` 的代码
        cc.addField("public static String[] pns;"); // property name array.
        // 添加静态属性 `pts` 的代码
        cc.addField("public static " + Map.class.getName() + " pts;"); // property type map.
        // 添加静态属性 `mns` 的代码
        cc.addField("public static String[] mns;"); // all method name array.
        // 添加静态属性 `dmns` 的代码
        cc.addField("public static String[] dmns;"); // declared method name array.
        // 添加静态属性 `mts` 的代码。每个方法的参数数组。
        for (int i = 0, len = ms.size(); i < len; i++) {
            cc.addField("public static Class[] mts" + i + ";");
        }

        // ======= 添加抽象方法的实现，到 `cc` 中
        // 添加 `#getPropertyNames()` 的代码到 `cc`
        cc.addMethod("public String[] getPropertyNames(){ return pns; }");
        // 添加 `#hasProperty(n)` 的代码到 `cc`
        cc.addMethod("public boolean hasProperty(String n){ return pts.containsKey($1); }");
        // 添加 `#getPropertyType(n)` 的代码到 `cc`
        cc.addMethod("public Class getPropertyType(String n){ return (Class)pts.get($1); }");
        // 添加 `#getMethodNames()` 的代码到 `cc`
        cc.addMethod("public String[] getMethodNames(){ return mns; }");
        // 添加 `#getDeclaredMethodNames()` 的代码到 `cc`
        cc.addMethod("public String[] getDeclaredMethodNames(){ return dmns; }");
        // 添加 `#setPropertyValue(o, n, v)` 的代码到 `cc`
        cc.addMethod(c1.toString());
        // 添加 `#getPropertyValue(o, n)` 的代码到 `cc`
        cc.addMethod(c2.toString());
        // 添加 `#invokeMethod(o, n, p, v)` 的代码到 `cc`
        cc.addMethod(c3.toString());

        try {
            // 生成类，通过javassist构建
            Class<?> wc = cc.toClass();

            // 反射，设置静态变量的值
            wc.getField("pts").set(null, pts);
            wc.getField("pns").set(null, pts.keySet().toArray(new String[0]));
            wc.getField("mns").set(null, mns.toArray(new String[0]));
            wc.getField("dmns").set(null, dmns.toArray(new String[0]));
            int ix = 0;
            for (Method m : ms.values()) {
                wc.getField("mts" + ix++).set(null, m.getParameterTypes());
            }
            // 通过反射创建对象 创建Wrapper 实例
            return (Wrapper) wc.newInstance();
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            // 释放资源
            cc.release();
            ms.clear();
            mns.clear();
            dmns.clear();
        }
    }

    private static String arg(Class<?> cl, String name) {
        if (cl.isPrimitive()) {
            if (cl == Boolean.TYPE) {
                return "((Boolean)" + name + ").booleanValue()";
            }
            if (cl == Byte.TYPE) {
                return "((Byte)" + name + ").byteValue()";
            }
            if (cl == Character.TYPE) {
                return "((Character)" + name + ").charValue()";
            }
            if (cl == Double.TYPE) {
                return "((Number)" + name + ").doubleValue()";
            }
            if (cl == Float.TYPE) {
                return "((Number)" + name + ").floatValue()";
            }
            if (cl == Integer.TYPE) {
                return "((Number)" + name + ").intValue()";
            }
            if (cl == Long.TYPE) {
                return "((Number)" + name + ").longValue()";
            }
            if (cl == Short.TYPE) {
                return "((Number)" + name + ").shortValue()";
            }
            throw new RuntimeException("Unknown primitive type: " + cl.getName());
        }
        return "(" + ReflectUtils.getName(cl) + ")" + name;
    }

    private static String args(Class<?>[] cs, String name) {
        int len = cs.length;
        if (len == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(arg(cs[i], name + "[" + i + "]"));
        }
        return sb.toString();
    }

    private static String propertyName(String pn) {
        return pn.length() == 1 || Character.isLowerCase(pn.charAt(1)) ? Character.toLowerCase(pn.charAt(0)) + pn.substring(1) : pn;
    }

    private static boolean hasMethods(Method[] methods) {
        if (methods == null || methods.length == 0) {
            return false;
        }
        for (Method m : methods) {
            // 有一个不是Object中定义的方法就说明符合条件
            if (m.getDeclaringClass() != Object.class) {
                return true;
            }
        }
        return false;
    }

    /**
     * get property name array.
     *
     * @return property name array.
     */
    abstract public String[] getPropertyNames();

    /**
     * get property type.
     *
     * @param pn property name.
     * @return Property type or nul.
     */
    abstract public Class<?> getPropertyType(String pn);

    /**
     * has property.
     *
     * @param name property name.
     * @return has or has not.
     */
    abstract public boolean hasProperty(String name);

    /**
     * get property value.
     *
     * @param instance instance.
     * @param pn       property name.
     * @return value.
     */
    abstract public Object getPropertyValue(Object instance, String pn) throws NoSuchPropertyException, IllegalArgumentException;

    /**
     * set property value.
     *
     * @param instance instance.
     * @param pn       property name.
     * @param pv       property value.
     */
    abstract public void setPropertyValue(Object instance, String pn, Object pv) throws NoSuchPropertyException, IllegalArgumentException;

    /**
     * get property value.
     *
     * @param instance instance.
     * @param pns      property name array.
     * @return value array.
     */
    public Object[] getPropertyValues(Object instance, String[] pns) throws NoSuchPropertyException, IllegalArgumentException {
        Object[] ret = new Object[pns.length];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = getPropertyValue(instance, pns[i]);
        }
        return ret;
    }

    /**
     * set property value.
     *
     * @param instance instance.
     * @param pns      property name array.
     * @param pvs      property value array.
     */
    public void setPropertyValues(Object instance, String[] pns, Object[] pvs) throws NoSuchPropertyException, IllegalArgumentException {
        if (pns.length != pvs.length) {
            throw new IllegalArgumentException("pns.length != pvs.length");
        }

        for (int i = 0; i < pns.length; i++) {
            setPropertyValue(instance, pns[i], pvs[i]);
        }
    }

    /**
     * get method name array.
     *
     * @return method name array.
     */
    abstract public String[] getMethodNames();

    /**
     * get method name array.
     *
     * @return method name array.
     */
    abstract public String[] getDeclaredMethodNames();

    /**
     * has method.
     *
     * @param name method name.
     * @return has or has not.
     */
    public boolean hasMethod(String name) {
        for (String mn : getMethodNames()) {
            if (mn.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 抽象方法，Dubbo在运行时会通过 Javassist 框架 为 Wrapper生成实现类，并实现该方法，该方法会根据调用信息调用具体的服务
     *
     * @param instance 被调用的对象
     * @param mn       方法名
     * @param types    参数类型数组
     * @param args     参数数组
     * @return 返回值
     */
    abstract public Object invokeMethod(Object instance, String mn, Class<?>[] types, Object[] args) throws NoSuchMethodException, InvocationTargetException;
}
