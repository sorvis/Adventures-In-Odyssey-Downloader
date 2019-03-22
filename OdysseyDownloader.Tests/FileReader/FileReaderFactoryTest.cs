using FluentAssertions;
using OdysseyDownloader.FileReader;
using System;
using System.Collections.Generic;
using System.Text;
using Xunit;

namespace OdysseyDownloader.Tests.FileReader
{
    public class FileReaderFactoryTest : IDisposable
    {
        private TestFileIndexScenerio _scenerio;
        private readonly FileReaderFactory it;
        private readonly FileIndexV1 _v1;

        public FileReaderFactoryTest()
        {
            _scenerio = new TestFileIndexScenerio();
            it = new FileReaderFactory(_scenerio.Config);
            _v1 = new FileIndexV1(_scenerio.Config);
        }

        public void Dispose()
        {
            _scenerio.Dispose();
        }

        [Fact]
        public void it_should_return_v1_when_index_is_found()
        {
            _scenerio.V1CreateTestMp3Files();
            _v1.RebuildIndex();
            var instance = it.Get();
            instance.Should().BeOfType<FileIndexV1>();
        }

        [Fact]
        public void it_should_return_highest_version_when_none_else_is_found()
        {
            var instance = it.Get();
            instance.Should().BeOfType<FileIndexV1>();
        }
    }
}
