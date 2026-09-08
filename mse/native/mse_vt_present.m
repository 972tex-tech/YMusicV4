#ifdef __APPLE__
#import <jni.h>

void *mse_vt_attach_component(void *env, void *component)
{
    /* Compose SwingPanel hosts a JPanel. JAWT GetDrawingSurfaceInfo on that
       peer SIGSEGVs in awt_DrawingSurface_GetDrawingSurfaceInfo (playground
       hs_err_pid13687, AWT-EventQueue-0). Keep CPU blit until we have a
       real Canvas/NSView, not this interop hole. */
    (void)env;
    (void)component;
    return NULL;
}
#endif

