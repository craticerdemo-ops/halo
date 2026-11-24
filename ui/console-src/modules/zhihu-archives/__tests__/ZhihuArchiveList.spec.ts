import type { SinglePage } from "@halo-dev/api-client";
import { utils } from "@halo-dev/ui-shared";
import messages from "@intlify/unplugin-vue-i18n/messages";
import { QueryClient, VueQueryPlugin } from "@tanstack/vue-query";
import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vite-plus/test";
import type { App } from "vue";
import { defineComponent, h } from "vue";
import { createI18n } from "vue-i18n";
import { createMemoryHistory, createRouter } from "vue-router";
import ZhihuArchiveList from "../ZhihuArchiveList.vue";

const {
  listZhihuArchivesMock,
  transferZhihuArchiveVideoMock,
  deleteZhihuArchiveMock,
  rebuildZhihuArchiveIndexMock,
  dialogWarningMock,
  toastSuccessMock,
  toastErrorMock,
} = vi.hoisted(() => ({
  listZhihuArchivesMock: vi.fn(),
  transferZhihuArchiveVideoMock: vi.fn(),
  deleteZhihuArchiveMock: vi.fn(),
  rebuildZhihuArchiveIndexMock: vi.fn(),
  dialogWarningMock: vi.fn(),
  toastSuccessMock: vi.fn(),
  toastErrorMock: vi.fn(),
}));

vi.mock("@halo-dev/api-client", () => ({
  consoleApiClient: {
    zhihuArchive: {
      listZhihuArchives: listZhihuArchivesMock,
      transferZhihuArchiveVideo: transferZhihuArchiveVideoMock,
      deleteZhihuArchive: deleteZhihuArchiveMock,
      rebuildZhihuArchiveIndex: rebuildZhihuArchiveIndexMock,
    },
  },
}));

vi.mock("@halo-dev/components", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@halo-dev/components")>();

  return {
    ...actual,
    Dialog: {
      warning: dialogWarningMock,
    },
    Toast: {
      success: toastSuccessMock,
      error: toastErrorMock,
    },
  };
});

const CAPTURED_AT = "2024-05-01T10:00:00Z";

function createArchive(options?: { withVideo?: boolean }): SinglePage {
  const annotations: Record<string, string> = {
    "zhihu-archive.halo.run/author-name": "Alice",
    "zhihu-archive.halo.run/source-type": "answer",
    "zhihu-archive.halo.run/captured-at": CAPTURED_AT,
  };

  if (options?.withVideo) {
    annotations["zhihu-archive.halo.run/video-state"] = "none";
    annotations["zhihu-archive.halo.run/video-source-url"] =
      "https://video.zhihu.com/source.mp4";
  }

  return {
    apiVersion: "content.halo.run/v1alpha1",
    kind: "SinglePage",
    metadata: {
      name: "zhihu-archive-1",
      annotations,
    },
    spec: {
      allowComment: false,
      deleted: false,
      excerpt: {
        autoGenerate: false,
        raw: "",
      },
      pinned: false,
      priority: 0,
      publish: true,
      slug: "archived-answer",
      title: "Archived answer",
      visible: "PRIVATE",
    },
  };
}

function mockList(items: SinglePage[]) {
  listZhihuArchivesMock.mockResolvedValue({
    data: {
      items,
      total: items.length,
    },
  });
}

const stubs = {
  Button: {
    emits: ["click"],
    template: `<button type="button" @click="$emit('click')"><slot /></button>`,
  },
  PageHeader: {
    props: ["title"],
    template: `<header><h1>{{ title }}</h1><slot name="actions" /><slot name="icon" /></header>`,
  },
  IconBookRead: true,
  IconExternalLinkLine: true,
  IconRefreshLine: true,
  VAlert: {
    props: ["description"],
    template: `<div class="alert">{{ description }}</div>`,
  },
  VCard: {
    template: `<div><slot /><slot name="footer" /></div>`,
  },
  VEmpty: {
    props: ["title", "message"],
    template: `<div><span>{{ title }}</span><span>{{ message }}</span><slot name="actions" /></div>`,
  },
  VLoading: true,
  VPagination: true,
};

function createQueryPlugin() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
      },
    },
  });

  return {
    install: (app: App) => {
      app.use(VueQueryPlugin, { queryClient });
    },
  };
}

function createRouterPlugin() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: "/",
        component: defineComponent({ render: () => h("div") }),
      },
    ],
  });
}

function mountList() {
  return mount(ZhihuArchiveList, {
    global: {
      plugins: [
        createQueryPlugin(),
        createRouterPlugin(),
        createI18n({
          legacy: false,
          locale: "en",
          messages,
        }),
      ],
      directives: {
        permission: () => undefined,
        tooltip: () => undefined,
      },
      stubs,
    },
  });
}

function findButtonByText(wrapper: ReturnType<typeof mountList>, text: string) {
  return wrapper
    .findAll("button")
    .find((button) => button.text().includes(text));
}

describe("ZhihuArchiveList", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders archived pages with annotations", async () => {
    mockList([createArchive()]);

    const wrapper = mountList();
    await flushPromises();

    expect(listZhihuArchivesMock).toHaveBeenCalledWith({ page: 1, size: 20 });
    expect(wrapper.text()).toContain("Archived answer");
    expect(wrapper.text()).toContain("Alice");
    expect(wrapper.text()).toContain("answer");
    expect(wrapper.text()).toContain(utils.date.format(CAPTURED_AT));
    expect(wrapper.text()).toContain("PRIVATE");

    const link = wrapper.find("a");
    expect(link.attributes("href")).toBe("/archives/archived-answer");
    expect(link.attributes("target")).toBe("_blank");
  });

  it("renders the empty state when there are no archives", async () => {
    mockList([]);

    const wrapper = mountList();
    await flushPromises();

    expect(wrapper.text()).toContain("There are no Zhihu archives yet.");
    expect(wrapper.text()).toContain(
      "Captured Zhihu content will appear here."
    );
  });

  it("transfers a video, shows a success toast and reloads the list", async () => {
    mockList([createArchive({ withVideo: true })]);
    transferZhihuArchiveVideoMock.mockResolvedValue({ data: {} });

    const wrapper = mountList();
    await flushPromises();

    const transferButton = findButtonByText(wrapper, "Transfer video");
    expect(transferButton).toBeDefined();

    await transferButton?.trigger("click");
    await flushPromises();

    expect(transferZhihuArchiveVideoMock).toHaveBeenCalledWith({
      name: "zhihu-archive-1",
    });
    expect(toastSuccessMock).toHaveBeenCalledWith(
      "Video transferred successfully"
    );
    expect(toastErrorMock).not.toHaveBeenCalled();
    expect(listZhihuArchivesMock).toHaveBeenCalledTimes(2);
  });

  it("shows the server message when the video transfer fails", async () => {
    mockList([createArchive({ withVideo: true })]);
    transferZhihuArchiveVideoMock.mockRejectedValue({
      response: {
        data: {
          message: "video unavailable",
        },
      },
    });

    const wrapper = mountList();
    await flushPromises();

    await findButtonByText(wrapper, "Transfer video")?.trigger("click");
    await flushPromises();

    expect(toastErrorMock).toHaveBeenCalledWith("video unavailable");
    expect(toastSuccessMock).not.toHaveBeenCalled();
    expect(listZhihuArchivesMock).toHaveBeenCalledTimes(1);
  });

  it("deletes an archive after confirmation, shows a success toast and reloads the list", async () => {
    mockList([createArchive()]);
    deleteZhihuArchiveMock.mockResolvedValue({ data: undefined });
    dialogWarningMock.mockImplementation(
      (options: { onConfirm: () => Promise<void> }) => options.onConfirm()
    );

    const wrapper = mountList();
    await flushPromises();

    await findButtonByText(wrapper, "Delete")?.trigger("click");
    await flushPromises();

    expect(dialogWarningMock).toHaveBeenCalledOnce();
    expect(deleteZhihuArchiveMock).toHaveBeenCalledWith({
      name: "zhihu-archive-1",
    });
    expect(toastSuccessMock).toHaveBeenCalledWith("Archive deleted");
    expect(toastErrorMock).not.toHaveBeenCalled();
    expect(listZhihuArchivesMock).toHaveBeenCalledTimes(2);
  });

  it("shows the server message when deleting fails", async () => {
    mockList([createArchive()]);
    deleteZhihuArchiveMock.mockRejectedValue({
      response: {
        data: {
          message: "cannot delete archive",
        },
      },
    });
    dialogWarningMock.mockImplementation(
      (options: { onConfirm: () => Promise<void> }) => options.onConfirm()
    );

    const wrapper = mountList();
    await flushPromises();

    await findButtonByText(wrapper, "Delete")?.trigger("click");
    await flushPromises();

    expect(toastErrorMock).toHaveBeenCalledWith("cannot delete archive");
    expect(toastSuccessMock).not.toHaveBeenCalled();
    expect(listZhihuArchivesMock).toHaveBeenCalledTimes(1);
  });

  it("rebuilds the index, shows a success toast and reloads the list", async () => {
    mockList([createArchive()]);
    rebuildZhihuArchiveIndexMock.mockResolvedValue({ data: {} });

    const wrapper = mountList();
    await flushPromises();

    await findButtonByText(wrapper, "Rebuild index")?.trigger("click");
    await flushPromises();

    expect(rebuildZhihuArchiveIndexMock).toHaveBeenCalledOnce();
    expect(toastSuccessMock).toHaveBeenCalledWith("Index rebuilt successfully");
    expect(toastErrorMock).not.toHaveBeenCalled();
    expect(listZhihuArchivesMock).toHaveBeenCalledTimes(2);
  });

  it("shows the error message when rebuilding the index fails", async () => {
    mockList([createArchive()]);
    rebuildZhihuArchiveIndexMock.mockRejectedValue(new Error("boom"));

    const wrapper = mountList();
    await flushPromises();

    await findButtonByText(wrapper, "Rebuild index")?.trigger("click");
    await flushPromises();

    expect(toastErrorMock).toHaveBeenCalledWith("boom");
    expect(toastSuccessMock).not.toHaveBeenCalled();
    expect(listZhihuArchivesMock).toHaveBeenCalledTimes(1);
  });
});
