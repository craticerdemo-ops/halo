<script lang="ts" setup>
import type { SinglePage } from "@halo-dev/api-client";
import { consoleApiClient } from "@halo-dev/api-client";
import {
  Dialog,
  IconBookRead,
  IconExternalLinkLine,
  IconRefreshLine,
  Toast,
  VAlert,
  VButton,
  VCard,
  VEmpty,
  VLoading,
  VPageHeader,
  VPagination,
} from "@halo-dev/components";
import { utils } from "@halo-dev/ui-shared";
import { useQuery } from "@tanstack/vue-query";
import { ref } from "vue";
import { useI18n } from "vue-i18n";

const { t } = useI18n();

const ANNO_AUTHOR_NAME = "zhihu-archive.halo.run/author-name";
const ANNO_SOURCE_TYPE = "zhihu-archive.halo.run/source-type";
const ANNO_CAPTURED_AT = "zhihu-archive.halo.run/captured-at";
const ANNO_VIDEO_STATE = "zhihu-archive.halo.run/video-state";
const ANNO_VIDEO_SOURCE_URL = "zhihu-archive.halo.run/video-source-url";

/**
 * Video state written by the backend when only the poster is archived and the
 * source video still needs to be transferred into attachment storage.
 */
const VIDEO_STATE_NONE = "none";

interface ApiErrorResponse {
  message?: string;
  detail?: string;
}

const page = ref(1);
const size = ref(20);
const total = ref(0);
const transferringName = ref<string>();
const deletingName = ref<string>();
const rebuilding = ref(false);

const {
  data: archives,
  isLoading,
  isFetching,
  refetch,
} = useQuery<SinglePage[]>({
  queryKey: ["zhihu-archives", page, size],
  queryFn: async () => {
    const { data } = await consoleApiClient.zhihuArchive.listZhihuArchives({
      page: page.value,
      size: size.value,
    });

    total.value = data.total;

    return data.items;
  },
});

function annotationOf(archive: SinglePage, key: string): string {
  return archive.metadata.annotations?.[key] || "";
}

function displayValue(value: string): string {
  return value || t("core.common.text.none");
}

function capturedAt(archive: SinglePage): string {
  const value = annotationOf(archive, ANNO_CAPTURED_AT);

  return value ? utils.date.format(value) : "";
}

function archiveUrl(archive: SinglePage): string {
  return archive.status?.permalink || `/archives/${archive.spec.slug}`;
}

function canTransferVideo(archive: SinglePage): boolean {
  return (
    annotationOf(archive, ANNO_VIDEO_STATE) === VIDEO_STATE_NONE &&
    !!annotationOf(archive, ANNO_VIDEO_SOURCE_URL)
  );
}

function resolveErrorMessage(error: unknown, fallback: string): string {
  if (typeof error === "object" && error !== null) {
    const response = (error as { response?: { data?: ApiErrorResponse } })
      .response;
    const message = response?.data?.message || response?.data?.detail;

    if (message) {
      return message;
    }

    if (error instanceof Error && error.message) {
      return error.message;
    }
  }

  return fallback;
}

async function handleTransferVideo(archive: SinglePage) {
  const name = archive.metadata.name;

  if (!name) {
    return;
  }

  transferringName.value = name;

  try {
    await consoleApiClient.zhihuArchive.transferZhihuArchiveVideo({ name });
    Toast.success(t("core.zhihu_archive.messages.transfer_success"));
    await refetch();
  } catch (error) {
    Toast.error(
      resolveErrorMessage(
        error,
        t("core.zhihu_archive.messages.transfer_error")
      )
    );
  } finally {
    transferringName.value = undefined;
  }
}

async function handleDelete(archive: SinglePage) {
  const name = archive.metadata.name;

  if (!name) {
    return;
  }

  Dialog.warning({
    title: t("core.zhihu_archive.operations.delete.title"),
    description: t("core.zhihu_archive.operations.delete.description"),
    confirmType: "danger",
    confirmText: t("core.common.buttons.delete"),
    cancelText: t("core.common.buttons.cancel"),
    onConfirm: async () => {
      deletingName.value = name;

      try {
        await consoleApiClient.zhihuArchive.deleteZhihuArchive({ name });
        Toast.success(t("core.zhihu_archive.messages.delete_success"));
        await refetch();
      } catch (error) {
        Toast.error(
          resolveErrorMessage(
            error,
            t("core.zhihu_archive.messages.delete_error")
          )
        );
      } finally {
        deletingName.value = undefined;
      }
    },
  });
}

async function handleRebuildIndex() {
  rebuilding.value = true;

  try {
    await consoleApiClient.zhihuArchive.rebuildZhihuArchiveIndex();
    Toast.success(t("core.zhihu_archive.messages.rebuild_success"));
    await refetch();
  } catch (error) {
    Toast.error(
      resolveErrorMessage(error, t("core.zhihu_archive.messages.rebuild_error"))
    );
  } finally {
    rebuilding.value = false;
  }
}
</script>

<template>
  <VPageHeader :title="$t('core.zhihu_archive.title')">
    <template #icon>
      <IconBookRead />
    </template>
    <template #actions>
      <button
        v-tooltip="$t('core.common.buttons.refresh')"
        class="group cursor-pointer rounded p-1 hover:bg-gray-200"
        type="button"
        @click="refetch()"
      >
        <IconRefreshLine
          :class="{ 'animate-spin text-gray-900': isFetching }"
          class="h-4 w-4 text-gray-600 group-hover:text-gray-900"
        />
      </button>
      <VButton
        v-permission="['system:zhihu-archives:manage']"
        :loading="rebuilding"
        size="sm"
        type="secondary"
        @click="handleRebuildIndex"
      >
        <template #icon>
          <IconRefreshLine />
        </template>
        {{ $t("core.zhihu_archive.actions.rebuild_index") }}
      </VButton>
    </template>
  </VPageHeader>

  <div class="m-0 md:m-4">
    <VAlert
      class="mb-3"
      :closable="false"
      :description="$t('core.zhihu_archive.privacy_notice')"
    />

    <VCard :body-class="['!p-0']">
      <VLoading v-if="isLoading" />
      <Transition v-else-if="!archives?.length" appear name="fade">
        <VEmpty
          :message="$t('core.zhihu_archive.empty.message')"
          :title="$t('core.zhihu_archive.empty')"
        >
          <template #actions>
            <VButton @click="refetch">
              {{ $t("core.common.buttons.refresh") }}
            </VButton>
          </template>
        </VEmpty>
      </Transition>
      <Transition v-else appear name="fade">
        <div class="overflow-x-auto">
          <table class="min-w-full divide-y divide-gray-100">
            <thead class="bg-gray-50">
              <tr>
                <th
                  class="px-4 py-3 text-left text-sm font-semibold text-gray-900"
                  scope="col"
                >
                  {{ $t("core.zhihu_archive.columns.title") }}
                </th>
                <th
                  class="px-4 py-3 text-left text-sm font-semibold text-gray-900"
                  scope="col"
                >
                  {{ $t("core.zhihu_archive.columns.author") }}
                </th>
                <th
                  class="px-4 py-3 text-left text-sm font-semibold text-gray-900"
                  scope="col"
                >
                  {{ $t("core.zhihu_archive.columns.source_type") }}
                </th>
                <th
                  class="px-4 py-3 text-left text-sm font-semibold text-gray-900"
                  scope="col"
                >
                  {{ $t("core.zhihu_archive.columns.captured_at") }}
                </th>
                <th
                  class="px-4 py-3 text-left text-sm font-semibold text-gray-900"
                  scope="col"
                >
                  {{ $t("core.zhihu_archive.columns.visibility") }}
                </th>
                <th
                  class="px-4 py-3 text-right text-sm font-semibold text-gray-900"
                  scope="col"
                >
                  {{ $t("core.zhihu_archive.columns.actions") }}
                </th>
              </tr>
            </thead>
            <tbody class="divide-y divide-gray-100 bg-white">
              <tr v-for="archive in archives" :key="archive.metadata.name">
                <td class="px-4 py-3 text-sm">
                  <a
                    :href="archiveUrl(archive)"
                    class="inline-flex items-center gap-1 font-medium text-gray-900 hover:text-primary"
                    rel="noopener noreferrer"
                    target="_blank"
                  >
                    {{ archive.spec.title }}
                    <IconExternalLinkLine
                      class="h-3.5 w-3.5 text-gray-400"
                      aria-hidden="true"
                    />
                  </a>
                </td>
                <td class="whitespace-nowrap px-4 py-3 text-sm text-gray-500">
                  {{ displayValue(annotationOf(archive, ANNO_AUTHOR_NAME)) }}
                </td>
                <td class="whitespace-nowrap px-4 py-3 text-sm text-gray-500">
                  {{ displayValue(annotationOf(archive, ANNO_SOURCE_TYPE)) }}
                </td>
                <td class="whitespace-nowrap px-4 py-3 text-sm text-gray-500">
                  {{ displayValue(capturedAt(archive)) }}
                </td>
                <td class="whitespace-nowrap px-4 py-3 text-sm text-gray-500">
                  {{ archive.spec.visible }}
                </td>
                <td class="whitespace-nowrap px-4 py-3 text-right text-sm">
                  <div class="flex items-center justify-end gap-2">
                    <VButton
                      v-if="canTransferVideo(archive)"
                      v-permission="['system:zhihu-archives:manage']"
                      :loading="transferringName === archive.metadata.name"
                      size="xs"
                      type="secondary"
                      @click="handleTransferVideo(archive)"
                    >
                      {{ $t("core.zhihu_archive.actions.transfer_video") }}
                    </VButton>
                    <VButton
                      v-permission="['system:zhihu-archives:manage']"
                      :loading="deletingName === archive.metadata.name"
                      size="xs"
                      type="danger"
                      @click="handleDelete(archive)"
                    >
                      {{ $t("core.zhihu_archive.actions.delete") }}
                    </VButton>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </Transition>

      <template #footer>
        <VPagination
          v-model:page="page"
          v-model:size="size"
          :page-label="$t('core.components.pagination.page_label')"
          :size-label="$t('core.components.pagination.size_label')"
          :total-label="
            $t('core.components.pagination.total_label', { total: total })
          "
          :total="total"
          :size-options="[20, 30, 50, 100]"
        />
      </template>
    </VCard>
  </div>
</template>
