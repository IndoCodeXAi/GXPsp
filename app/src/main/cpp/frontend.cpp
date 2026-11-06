// Frontend libretro dengan hardware render (OpenGL ES 3) untuk core PPSSPP.
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "MC3", __VA_ARGS__)

typedef void (*proc_t)(void);
typedef proc_t (*get_proc_t)(const char *);
typedef uintptr_t (*get_fb_t)(void);

struct retro_game_info { const char *path; const void *data; size_t size; const char *meta; };
struct retro_system_av_info {
    struct { unsigned base_width, base_height, max_width, max_height; float aspect_ratio; } geometry;
    struct { double fps, sample_rate; } timing;
};
struct retro_hw_render_callback {
    unsigned context_type;
    void (*context_reset)(void);
    get_fb_t get_current_framebuffer;
    get_proc_t get_proc_address;
    bool depth, stencil, bottom_left_origin;
    unsigned version_major, version_minor;
    bool cache_context;
    void (*context_destroy)(void);
    bool debug_context;
};

typedef bool (*env_t)(unsigned, void *);
typedef void (*video_t)(const void *, unsigned, unsigned, size_t);
typedef void (*audio_t)(int16_t, int16_t);
typedef size_t (*audio_batch_t)(const int16_t *, size_t);
typedef void (*poll_t)(void);
typedef int16_t (*input_t)(unsigned, unsigned, unsigned, unsigned);

static void *g_lib = nullptr;
static void (*r_init)(void);
static void (*r_deinit)(void);
static void (*r_set_env)(env_t);
static void (*r_set_video)(video_t);
static void (*r_set_audio)(audio_t);
static void (*r_set_audio_batch)(audio_batch_t);
static void (*r_set_poll)(poll_t);
static void (*r_set_input)(input_t);
static bool (*r_load)(const retro_game_info *);
static void (*r_unload)(void);
static void (*r_run)(void);
static void (*r_av_info)(retro_system_av_info *);

static std::string g_sys, g_save, g_rom;
static retro_hw_render_callback g_hw{};
static bool g_has_hw = false;
static retro_system_av_info g_av{};
static std::vector<int16_t> g_audio;
static std::atomic<uint32_t> g_pad{0};
static std::atomic<int> g_ax{0}, g_ay{0};
static unsigned g_w = 0, g_h = 0;
static bool g_have = false;

static GLuint g_fbo = 0, g_tex = 0, g_rb = 0, g_prog = 0, g_vbo = 0;
static int g_fbw = 480, g_fbh = 272, g_sw = 1, g_sh = 1;

static uintptr_t get_fb_cb(void) { return g_fbo; }
static proc_t get_proc_cb(const char *s) { return (proc_t)eglGetProcAddress(s); }

static bool env_cb(unsigned cmd, void *data) {
    cmd &= 0xFFFF;
    switch (cmd) {
        case 3: *(bool *)data = true; return true;                 // CAN_DUPE
        case 9: case 30: *(const char **)data = g_sys.c_str(); return true;  // SYSTEM / CORE_ASSETS dir
        case 31: *(const char **)data = g_save.c_str(); return true;         // SAVE dir
        case 10: return true;                                      // PIXEL_FORMAT
        case 14: {                                                 // SET_HW_RENDER
            auto *hw = (retro_hw_render_callback *)data;
            if (hw->context_type != 2 && hw->context_type != 4 && hw->context_type != 5) return false;
            hw->get_current_framebuffer = get_fb_cb;
            hw->get_proc_address = get_proc_cb;
            g_hw = *hw; g_has_hw = true;
            return true;
        }
        case 56: *(unsigned *)data = 4; return true;               // PREFERRED_HW_RENDER = GLES3
        default: return false;
    }
}

static void video_cb(const void *data, unsigned w, unsigned h, size_t) {
    if (data == (const void *)(intptr_t)-1) { g_w = w; g_h = h; g_have = true; }
}
static void audio_cb(int16_t l, int16_t r) { g_audio.push_back(l); g_audio.push_back(r); }
static size_t audio_batch_cb(const int16_t *d, size_t frames) {
    g_audio.insert(g_audio.end(), d, d + frames * 2);
    return frames;
}
static void poll_cb(void) {}
static int16_t input_cb(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0) return 0;
    if (device == 1) {  // JOYPAD
        uint32_t m = g_pad.load();
        if (id == 256) return (int16_t)m;
        return id < 16 ? (int16_t)((m >> id) & 1) : 0;
    }
    if (device == 5 && index == 0) {  // ANALOG kiri
        return (int16_t)(id == 0 ? g_ax.load() : id == 1 ? g_ay.load() : 0);
    }
    return 0;
}

static std::string jstr(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r = c ? c : "";
    env->ReleaseStringUTFChars(s, c);
    return r;
}

static GLuint compile(GLenum type, const char *src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0; glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) { char buf[512]; glGetShaderInfoLog(s, 512, nullptr, buf); LOGI("shader: %s", buf); }
    return s;
}

static bool create_gl() {
    g_fbw = (int)std::min(std::max(g_av.geometry.max_width, g_av.geometry.base_width), 1920u);
    g_fbh = (int)std::min(std::max(g_av.geometry.max_height, g_av.geometry.base_height), 1088u);
    if (g_fbw < 16 || g_fbh < 16) { g_fbw = 480; g_fbh = 272; }

    glGenTextures(1, &g_tex);
    glBindTexture(GL_TEXTURE_2D, g_tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, g_fbw, g_fbh, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glGenRenderbuffers(1, &g_rb);
    glBindRenderbuffer(GL_RENDERBUFFER, g_rb);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, g_fbw, g_fbh);

    glGenFramebuffers(1, &g_fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, g_fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, g_tex, 0);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, g_rb);
    bool ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (!ok) { LOGI("FBO tidak lengkap"); return false; }

    const char *vs = "#version 300 es\nlayout(location=0) in vec2 p; layout(location=1) in vec2 t; out vec2 uv;\n"
                     "void main(){ gl_Position = vec4(p,0.0,1.0); uv = t; }";
    const char *fs = "#version 300 es\nprecision mediump float; in vec2 uv; uniform sampler2D s; out vec4 c;\n"
                     "void main(){ c = vec4(texture(s, uv).rgb, 1.0); }";
    g_prog = glCreateProgram();
    glAttachShader(g_prog, compile(GL_VERTEX_SHADER, vs));
    glAttachShader(g_prog, compile(GL_FRAGMENT_SHADER, fs));
    glLinkProgram(g_prog);
    glGenBuffers(1, &g_vbo);
    return true;
}

static void draw_frame() {
    if (!g_have || !g_prog) return;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, g_sw, g_sh);
    glDisable(GL_DEPTH_TEST); glDisable(GL_STENCIL_TEST); glDisable(GL_BLEND);
    glDisable(GL_SCISSOR_TEST); glDisable(GL_CULL_FACE);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);

    glUseProgram(g_prog);
    glBindVertexArray(0);
    for (int i = 0; i < 16; i++) glDisableVertexAttribArray(i);
    glBindBuffer(GL_ARRAY_BUFFER, g_vbo);

    float u = std::min(1.0f, (float)g_w / g_fbw), v = std::min(1.0f, (float)g_h / g_fbh);
    float v0 = g_hw.bottom_left_origin ? 0.f : v, v1 = g_hw.bottom_left_origin ? v : 0.f;
    float verts[16] = {-1, -1, 0, v0, 1, -1, u, v0, -1, 1, 0, v1, 1, 1, u, v1};
    glBufferData(GL_ARRAY_BUFFER, sizeof(verts), verts, GL_STREAM_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 16, (void *)0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 16, (void *)8);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, g_tex);
    glUniform1i(glGetUniformLocation(g_prog, "s"), 0);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

#define SYM(var, name) *(void **)(&var) = dlsym(g_lib, name)

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_mc3dub_app_Native_start(JNIEnv *env, jobject, jstring rom, jstring sys, jstring save) {
    g_rom = jstr(env, rom); g_sys = jstr(env, sys); g_save = jstr(env, save);
    g_lib = dlopen("libppsspp_core.so", RTLD_NOW);
    if (!g_lib) { LOGI("dlopen gagal: %s", dlerror()); return JNI_FALSE; }
    SYM(r_init, "retro_init"); SYM(r_deinit, "retro_deinit");
    SYM(r_set_env, "retro_set_environment"); SYM(r_set_video, "retro_set_video_refresh");
    SYM(r_set_audio, "retro_set_audio_sample"); SYM(r_set_audio_batch, "retro_set_audio_sample_batch");
    SYM(r_set_poll, "retro_set_input_poll"); SYM(r_set_input, "retro_set_input_state");
    SYM(r_load, "retro_load_game"); SYM(r_unload, "retro_unload_game"); SYM(r_run, "retro_run");
    SYM(r_av_info, "retro_get_system_av_info");
    if (!r_init || !r_set_env || !r_load || !r_run || !r_av_info) { LOGI("simbol core kurang"); return JNI_FALSE; }

    r_set_env(env_cb);
    r_set_video(video_cb); r_set_audio(audio_cb); r_set_audio_batch(audio_batch_cb);
    r_set_poll(poll_cb); r_set_input(input_cb);
    r_init();

    retro_game_info gi{g_rom.c_str(), nullptr, 0, nullptr};  // PPSSPP memakai path penuh
    if (!r_load(&gi)) { LOGI("retro_load_game gagal"); return JNI_FALSE; }
    if (!g_has_hw) { LOGI("core tidak meminta HW render"); return JNI_FALSE; }
    r_av_info(&g_av);
    return JNI_TRUE;
}

JNIEXPORT jdoubleArray JNICALL Java_com_mc3dub_app_Native_info(JNIEnv *env, jobject) {
    jdoubleArray a = env->NewDoubleArray(2);
    double v[2] = {g_av.timing.sample_rate > 0 ? g_av.timing.sample_rate : 44100.0,
                   g_av.timing.fps > 0 ? g_av.timing.fps : 60.0};
    env->SetDoubleArrayRegion(a, 0, 2, v);
    return a;
}

JNIEXPORT jboolean JNICALL Java_com_mc3dub_app_Native_initGl(JNIEnv *, jobject) {
    g_have = false;
    if (!create_gl()) return JNI_FALSE;
    if (g_hw.context_reset) g_hw.context_reset();
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_mc3dub_app_Native_resize(JNIEnv *, jobject, jint w, jint h) { g_sw = w; g_sh = h; }

JNIEXPORT void JNICALL Java_com_mc3dub_app_Native_runFrame(JNIEnv *, jobject) {
    g_audio.clear();
    r_run();
}

JNIEXPORT void JNICALL Java_com_mc3dub_app_Native_draw(JNIEnv *, jobject) { draw_frame(); }

JNIEXPORT jint JNICALL Java_com_mc3dub_app_Native_audio(JNIEnv *env, jobject, jshortArray out) {
    jsize cap = env->GetArrayLength(out);
    jsize n = (jsize)std::min<size_t>((size_t)cap, g_audio.size());
    env->SetShortArrayRegion(out, 0, n, (const jshort *)g_audio.data());
    return n;
}

JNIEXPORT void JNICALL Java_com_mc3dub_app_Native_setInput(JNIEnv *, jobject, jint mask, jint ax, jint ay) {
    g_pad.store((uint32_t)mask); g_ax.store(ax); g_ay.store(ay);
}

JNIEXPORT void JNICALL Java_com_mc3dub_app_Native_stop(JNIEnv *, jobject) {
    if (g_has_hw && g_hw.context_destroy) g_hw.context_destroy();
    if (r_unload) r_unload();
    if (r_deinit) r_deinit();
    // core tidak di-dlclose; proses game dimatikan oleh GameActivity
}

} // extern "C"
