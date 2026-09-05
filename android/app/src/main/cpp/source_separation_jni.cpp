#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>
#include <vector>

namespace {
constexpr const char* kTag = "LingoPlaySeparation";
constexpr const char* kSherpaLibrary = "libsherpa-onnx-c-api.so";

struct SherpaOnnxOfflineSourceSeparationSpleeterModelConfig {
  const char* vocals;
  const char* accompaniment;
};
struct SherpaOnnxOfflineSourceSeparationUvrModelConfig { const char* model; };
struct SherpaOnnxOfflineSourceSeparationModelConfig {
  SherpaOnnxOfflineSourceSeparationSpleeterModelConfig spleeter;
  SherpaOnnxOfflineSourceSeparationUvrModelConfig uvr;
  int32_t num_threads;
  int32_t debug;
  const char* provider;
};
struct SherpaOnnxOfflineSourceSeparationConfig {
  SherpaOnnxOfflineSourceSeparationModelConfig model;
};
struct SherpaOnnxOfflineSourceSeparation;
struct SherpaOnnxSourceSeparationStem {
  float** samples;
  int32_t num_channels;
  int32_t n;
};
struct SherpaOnnxSourceSeparationOutput {
  const SherpaOnnxSourceSeparationStem* stems;
  int32_t num_stems;
  int32_t sample_rate;
};

using CreateFn = const SherpaOnnxOfflineSourceSeparation* (*)(
    const SherpaOnnxOfflineSourceSeparationConfig*);
using DestroyFn = void (*)(const SherpaOnnxOfflineSourceSeparation*);
using ProcessFn = const SherpaOnnxSourceSeparationOutput* (*)(
    const SherpaOnnxOfflineSourceSeparation*, const float* const*, int32_t,
    int32_t, int32_t);
using DestroyOutputFn = void (*)(const SherpaOnnxSourceSeparationOutput*);
using WriteWaveFn = int32_t (*)(const float* const*, int32_t, int32_t, int32_t,
                                const char*);

struct SherpaApi {
  void* handle = nullptr;
  CreateFn create = nullptr;
  DestroyFn destroy = nullptr;
  ProcessFn process = nullptr;
  DestroyOutputFn destroy_output = nullptr;
  WriteWaveFn write_wave = nullptr;

  ~SherpaApi() {
    if (handle != nullptr) dlclose(handle);
  }

  bool Load() {
    handle = dlopen(kSherpaLibrary, RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) return false;
    create = reinterpret_cast<CreateFn>(dlsym(handle, "SherpaOnnxCreateOfflineSourceSeparation"));
    destroy = reinterpret_cast<DestroyFn>(dlsym(handle, "SherpaOnnxDestroyOfflineSourceSeparation"));
    process = reinterpret_cast<ProcessFn>(dlsym(handle, "SherpaOnnxOfflineSourceSeparationProcess"));
    destroy_output = reinterpret_cast<DestroyOutputFn>(dlsym(handle, "SherpaOnnxDestroySourceSeparationOutput"));
    write_wave = reinterpret_cast<WriteWaveFn>(dlsym(handle, "SherpaOnnxWriteWaveMultiChannel"));
    return create != nullptr && destroy != nullptr && process != nullptr &&
           destroy_output != nullptr && write_wave != nullptr;
  }
};

class UtfChars {
 public:
  UtfChars(JNIEnv* env, jstring value) : env_(env), value_(value) {
    chars_ = value_ == nullptr ? nullptr : env_->GetStringUTFChars(value_, nullptr);
  }
  ~UtfChars() {
    if (chars_ != nullptr) env_->ReleaseStringUTFChars(value_, chars_);
  }
  const char* get() const { return chars_; }
 private:
  JNIEnv* env_;
  jstring value_;
  const char* chars_ = nullptr;
};

bool IsValidPath(const UtfChars& path) {
  return path.get() != nullptr && path.get()[0] != '\0';
}

void LogError(const char* message) {
  __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", message);
}
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_lingoplay_app_SourceSeparationNative_nativeRuntimeAvailable(
    JNIEnv*, jclass) {
  SherpaApi api;
  return api.Load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_lingoplay_app_SourceSeparationNative_nativeSeparateChunk(
    JNIEnv* env, jclass, jstring vocals_model, jstring accompaniment_model,
    jfloatArray planar_stereo, jint frames, jint sample_rate,
    jlong process_start_frame, jlong core_start_frame, jint core_frames,
    jstring vocals_output, jstring accompaniment_output) {
  if (frames <= 0 || sample_rate <= 0 || core_frames <= 0 || planar_stereo == nullptr) {
    return JNI_FALSE;
  }
  if (process_start_frame < 0 || core_start_frame < process_start_frame) return JNI_FALSE;
  if (env->GetArrayLength(planar_stereo) != frames * 2) return JNI_FALSE;

  UtfChars vocals_model_path(env, vocals_model);
  UtfChars accompaniment_model_path(env, accompaniment_model);
  UtfChars vocals_output_path(env, vocals_output);
  UtfChars accompaniment_output_path(env, accompaniment_output);
  if (!IsValidPath(vocals_model_path) || !IsValidPath(accompaniment_model_path) ||
      !IsValidPath(vocals_output_path) || !IsValidPath(accompaniment_output_path)) {
    return JNI_FALSE;
  }

  SherpaApi api;
  if (!api.Load()) {
    LogError("Unable to load sherpa-onnx source-separation C API");
    return JNI_FALSE;
  }

  SherpaOnnxOfflineSourceSeparationConfig config{};
  config.model.spleeter.vocals = vocals_model_path.get();
  config.model.spleeter.accompaniment = accompaniment_model_path.get();
  config.model.num_threads = 1;
  config.model.debug = 0;
  config.model.provider = "cpu";

  const SherpaOnnxOfflineSourceSeparation* separator = api.create(&config);
  if (separator == nullptr) {
    LogError("Unable to initialize Spleeter source separator");
    return JNI_FALSE;
  }

  jfloat* samples = env->GetFloatArrayElements(planar_stereo, nullptr);
  if (samples == nullptr) {
    api.destroy(separator);
    return JNI_FALSE;
  }
  const float* channels[2] = {samples, samples + frames};
  const SherpaOnnxSourceSeparationOutput* output =
      api.process(separator, channels, 2, frames, sample_rate);
  env->ReleaseFloatArrayElements(planar_stereo, samples, JNI_ABORT);

  if (output == nullptr || output->num_stems != 2 || output->sample_rate <= 0) {
    if (output != nullptr) api.destroy_output(output);
    api.destroy(separator);
    LogError("Spleeter returned an invalid two-stem contract");
    return JNI_FALSE;
  }

  const auto map_frame = [sample_rate, output](int64_t frame) -> int64_t {
    return static_cast<int64_t>(std::llround(
        static_cast<double>(frame) * static_cast<double>(output->sample_rate) /
        static_cast<double>(sample_rate)));
  };
  const int64_t process_output_start = map_frame(process_start_frame);
  const int64_t core_output_start = map_frame(core_start_frame);
  const int64_t core_output_end = map_frame(core_start_frame + core_frames);
  const int64_t crop_start = core_output_start - process_output_start;
  const int64_t expected_core_frames = core_output_end - core_output_start;
  if (crop_start < 0 || expected_core_frames <= 0) {
    api.destroy_output(output);
    api.destroy(separator);
    return JNI_FALSE;
  }

  bool ok = true;
  const char* paths[2] = {vocals_output_path.get(), accompaniment_output_path.get()};
  for (int stem_index = 0; stem_index < 2; ++stem_index) {
    const SherpaOnnxSourceSeparationStem& stem = output->stems[stem_index];
    if (stem.samples == nullptr || stem.num_channels <= 0 || stem.n <= 0 ||
        crop_start >= stem.n) {
      ok = false;
      break;
    }
    const int64_t available = static_cast<int64_t>(stem.n) - crop_start;
    const int32_t frames_to_write = static_cast<int32_t>(
        std::min<int64_t>(available, expected_core_frames));
    if (frames_to_write <= 0) {
      ok = false;
      break;
    }
    std::vector<const float*> stem_samples(static_cast<size_t>(stem.num_channels));
    for (int32_t channel = 0; channel < stem.num_channels; ++channel) {
      if (stem.samples[channel] == nullptr) {
        ok = false;
        break;
      }
      stem_samples[static_cast<size_t>(channel)] = stem.samples[channel] + crop_start;
    }
    if (!ok) break;
    if (api.write_wave(stem_samples.data(), frames_to_write, output->sample_rate,
                       stem.num_channels, paths[stem_index]) != 1) {
      ok = false;
      break;
    }
  }

  api.destroy_output(output);
  api.destroy(separator);
  return ok ? JNI_TRUE : JNI_FALSE;
}
