package org.jdownloader.scripting;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import org.appwork.exceptions.WTFException;
import org.appwork.utils.logging.Log;

import sun.org.mozilla.javascript.internal.ClassShutter;
import sun.org.mozilla.javascript.internal.Context;
import sun.org.mozilla.javascript.internal.ContextFactory;
import sun.org.mozilla.javascript.internal.EcmaError;
import sun.org.mozilla.javascript.internal.NativeJavaObject;
import sun.org.mozilla.javascript.internal.Scriptable;
import sun.org.mozilla.javascript.internal.WrapFactory;

/**
 * from http://codeutopia.net/blog/2009/01/02/sandboxing-rhino-in-java/
 * 
 * ============================================================================ =========
 * 
 * Sandboxing Rhino in Java January 2, 2009 – 12:07 am Tags: Java, JavaScript, Security I’ve been working on a Java app which needed Rhino
 * for scripting. The app would need to run untrusted JavaScript code from 3rd parties, so I had to find a way to block access to all Java
 * methods, except the ones I wanted. This would not be a problem if there was an easy way to disable LiveConnect - the feature of Rhino
 * which provides java access to scripts - but there is no such thing.
 * 
 * However, after a lot of digging around, I finally found a way to do this without too much hacking. In fact, it can be done by just
 * extending a few of the Rhino classes, and using the setters provided to override some of the default ones.
 * 
 * 
 * ClassShutter
 * 
 * Let’s first look at the ClassShutter, which can be used to restrict access to Java packages and classes.
 * 
 * //cx is the Context instance you're using to run scripts cx.setClassShutter(new ClassShutter() { public boolean visibleToScripts(String
 * className) { if(className.startsWith("adapter")) return true;
 * 
 * return false; } }); The above will effectively disable access to all Java classes onwards from the point where the shutter was set.
 * However, if you run any scripts before setting the shutter, classes accessed there can still be used! You can use this to your advantage,
 * for example to provide specific classes in the scripts under different names or such.
 * 
 * You probably noticed the comparison to “adapter” in the shutter. This is for when you implement interfaces or extend Java classes. Rhino
 * will create new classes based on those interfaces/classes, and they will be called adapterN, where N is a number. If you block access to
 * classes starting with adapter, you can’t implement or extend, and my use-case required that.
 * 
 * However, there is a limitation in the ClassShutter…
 * 
 * Reflection
 * 
 * As you may know, you can use someInstance.getClass().forName(”some.package.Class”).newInstance() to get a new instance of
 * some.package.Class.
 * 
 * This will not get blocked by the ClassShutter! We need to disable access to getClass() to block this.
 * 
 * While the ClassShutter is relatively well documented, doing this required more research. A post in the Rhino mailing list finally pushed
 * me to the right direction: Overriding certain NativeJavaObject methods and creating a custom ContextFactory and WrapFactory for that.
 * 
 * Here is an extended NativeJavaObject, which blocks access to getClass. You could use this approach to block access to other methods too:
 * 
 * public static class SandboxNativeJavaObject extends NativeJavaObject { public SandboxNativeJavaObject(Scriptable scope, Object
 * javaObject, Class staticType) { super(scope, javaObject, staticType); }
 * 
 * @Override public Object get(String name, Scriptable start) { if (name.equals("getClass")) { return NOT_FOUND; }
 * 
 *           return super.get(name, start); } } To make the above class work, you need two more classes:
 * 
 *           A WrapFactory which returns our SandboxNativeJavaObject’s
 * 
 *           public static class SandboxWrapFactory extends WrapFactory {
 * @Override public Scriptable wrapAsJavaObject(Context cx, Scriptable scope, Object javaObject, Class staticType) { return new
 *           SandboxNativeJavaObject(scope, javaObject, staticType); } } And a ContextFactory, which returns Context’s which use
 *           SandboxWrapFactory:
 * 
 *           public class SandboxContextFactory extends ContextFactory {
 * @Override protected Context makeContext() { Context cx = super.makeContext(); cx.setWrapFactory(new SandboxWrapFactory()); return cx; } }
 *           Finally, to make all this work, we need to tell Rhino the global ContextFactory:
 * 
 *           ContextFactory.initGlobal(new SandboxContextFactory()); With this, we are done. Now, when you use
 *           ContextFactory.getGlobal().enterContext(), you will get sandboxing contexts. But why did we need to set it globally? This is
 *           because it would appear that certain things, such as the adapter classes, use the global context factory to get some context
 *           for themselves, and without setting the global factory, they would get unlimited access.
 * 
 *           In closing
 * 
 *           I hope this is useful for someone. It took me a long time to figure it all out, so here it is now, all documented in one place.
 *           =)
 * 
 *           The mailing list post where I found the direction for blocking getClass can be found here. Thanks to Charles Lowell.
 * 
 *           There is also the SecurityController, which may be useful in further securing the class.
 * 
 *           And as a final warning, while this approach works for me, and I haven’t yet found any way to get past the sandboxing and into
 *           Java-land… but there may be a way, and if you find one, do let me know.
 * 
 * 
 * 
 *           ================================================================== == =================
 * @author thomas
 * 
 */
public class JSPermissionRestricter {
    static public class SandboxContextFactory extends ContextFactory {
        private ContextFactory rhinoFactory;
        private Method         makeContextMethod;

        public SandboxContextFactory(ContextFactory rhinoFactory) throws NoSuchMethodException, SecurityException {
            this.rhinoFactory = rhinoFactory;
            makeContextMethod = ContextFactory.class.getDeclaredMethod("makeContext", new Class[] {});
            makeContextMethod.setAccessible(true);
        }

        @Override
        protected Context makeContext() {
            Context cx;
            try {
                cx = (Context) makeContextMethod.invoke(rhinoFactory, new Object[] {});

                Field classShutterInstance = Context.class.getDeclaredField("classShutter");
                classShutterInstance.setAccessible(true);

                final ClassShutter oldShutter = (ClassShutter) classShutterInstance.get(cx);

                cx.setWrapFactory(new SandboxWrapFactory(cx.getWrapFactory()));

                classShutterInstance.set(cx, new ClassShutter() {
                    public boolean visibleToScripts(String className) {
                        if (!oldShutter.visibleToScripts(className)) {
                            return false;
                        }
                        Thread cur = Thread.currentThread();
                        if (TRUSTED_THREAD.containsKey(cur)) {
                            Log.L.severe("Trusted Thread Loads: " + className);
                            return true;

                        }
                        if (className.startsWith("adapter")) {
                            return true;

                        } else if (className.equals("sun.org.mozilla.javascript.internal.EcmaError")) {
                            Log.L.severe("Javascript error occured");
                            return true;
                        } else {

                            throw new RuntimeException("Security Violation " + className);
                        }

                    }
                });

                return cx;
            } catch (Throwable e) {
                throw new WTFException(e);
            }
        }
    }

    public static class SandboxWrapFactory extends WrapFactory {

        private WrapFactory rhinoFactory;

        public SandboxWrapFactory(WrapFactory wrapFactory) {
            this.rhinoFactory = wrapFactory;
        }

        @SuppressWarnings("rawtypes")
        @Override
        public Scriptable wrapAsJavaObject(Context cx, Scriptable scope, Object javaObject, Class staticType) {

            if (javaObject instanceof EcmaError) {
                Log.exception((EcmaError) javaObject);
            }
            return new SandboxNativeJavaObject(scope, javaObject, staticType);
        }

    }

    public static class SandboxNativeJavaObject extends NativeJavaObject {

        private static final long serialVersionUID = -2783084485265910840L;

        public SandboxNativeJavaObject(Scriptable scope, Object javaObject, Class<?> staticType) {
            super(scope, javaObject, staticType);
        }

        @Override
        public Object get(String name, Scriptable start) {

            if (name.equals("getClass")) {
                Log.L.severe("JS Security Exception");
                return NOT_FOUND;
            }

            return super.get(name, start);

        }
    }

    public static void init() {
        // this is onlypossible before RhineScriptEngine is loaded
        // GlobalSetter getGlobalSetter = ContextFactory.getGlobalSetter();
        try {
            // force init
            ScriptEngineManager manager = new ScriptEngineManager();
            ScriptEngine engine = manager.getEngineByName("javascript");

            engine.eval("java.lang.System.out.println('TEST')");

            Field globalField = ContextFactory.class.getDeclaredField("global");

            globalField.setAccessible(true);
            Object global = globalField.get(null);
            globalField.set(null, new SandboxContextFactory((ContextFactory) global));
            // try again. now this native call should throw an exception.
            try {

                manager = new ScriptEngineManager();
                engine = manager.getEngineByName("javascript");

                engine.eval("java.lang.System.out.println('TEST')");
                throw new SecurityException("Could not install the sun.org.mozilla.javascript.internal Sandbox!");
            } catch (RuntimeException e) {
                // this time, an exception is fine

            }

        } catch (Throwable e) {
            e.printStackTrace();
        }

    }

    private static ConcurrentHashMap<Thread, Boolean> TRUSTED_THREAD = new ConcurrentHashMap<Thread, Boolean>();

}