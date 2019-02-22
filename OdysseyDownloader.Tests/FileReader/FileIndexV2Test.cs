using Adventures_In_Odyssey_Downloader.FileReaderV2;
using FluentAssertions;
using System;
using System.IO;
using System.Linq;
using Xunit;

namespace OdysseyDownloader.Tests.FileReader
{
    public class FileIndexV2Test : IDisposable
    {
        private readonly FileIndexV2 it;
        private readonly TestFileIndexScenerio _scenerio;

        public FileIndexV2Test()
        {
            _scenerio = new TestFileIndexScenerio();
            it = new FileIndexV2(_scenerio.Config);
            _scenerio.CreateTestMp3Files();
        }

        public void Dispose()
        {
            _scenerio.Dispose();
        }

        [Fact]
        public void it_should_find_each_title()
        {
            var result = it.RebuildIndex();
            var actualTitles = result.Select(x => x.Title);
            actualTitles.Should().BeEquivalentTo(_scenerio.Titles);
        }

        [Fact]
        public void it_should_find_each_file_name()
        {
            var result = it.RebuildIndex();
            var actual = result.Select(x => x.FileName);
            var expectedFileNames = _scenerio.GetFileNamesCreated();
            actual.Should().BeEquivalentTo(expectedFileNames);
        }

        [Fact]
        public void it_should_find_each_episode_number()
        {
            var result = it.RebuildIndex();
            var actual = result.Select(x => x.Number);
            var expected = _scenerio.EpisodeNumbers.Select(x => x.ToString());
            actual.Should().BeEquivalentTo(expected);
        }

        [Fact]
        public void it_should_read_back_same_as_Rebuild_created()
        {
            var expected = it.RebuildIndex();
            var actual = it.ReadIndex();
            actual.Should().BeEquivalentTo(expected);
        }

        [Fact]
        public void it_should_know_if_index_file_exists()
        {
            it.RebuildIndex();
            it.IndexDetected().Should().BeTrue();
        }

        [Fact]
        public void it_should_know_if_index_file_does_not_exist()
        {
            it.IndexDetected().Should().BeFalse();
        }

        [Fact]
        public void it_should_know_if_index_file_is_in_unknown_format()
        {
            var indexFileName = _scenerio.Config.GetIndexFilePath();
            File.WriteAllText(indexFileName, Guid.NewGuid().ToString());
            it.IndexDetected().Should().BeFalse();
        }
    }
}
