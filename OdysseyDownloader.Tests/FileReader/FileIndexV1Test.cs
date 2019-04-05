using FluentAssertions;
using OdysseyDownloader.FileReader;
using System;
using System.IO;
using System.Linq;
using Xunit;

namespace OdysseyDownloader.Tests.FileReader
{
    public class FileIndexV1Test: IDisposable
    {
        private readonly FileIndexV1 it;
        private readonly TestFileIndexScenerio _scenerio;

        public FileIndexV1Test()
        {
            _scenerio = new TestFileIndexScenerio();
            it = new FileIndexV1(_scenerio.Config);
            _scenerio.V1CreateTestMp3Files();
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

        [Fact]
        public void it_should_create_new_index_on_WriteToIndex_if_none_exists()
        {
            var expected = _scenerio.GenerateAudioFile();
            expected.Date = default(DateTime); // v1 does not support storing the date
            expected.FileName = $"{expected.Number}#-{expected.Title}.mp3";

            it.WriteToIndex(expected);
            var actual = it.ReadIndex();

            actual.Should().BeEquivalentTo(new[] { expected });
        }

        [Fact]
        public void it_should_append_to_index_on_WriteToIndex_if_one_exists()
        {
            var addOnFile = _scenerio.GenerateAudioFile();
            var expected = it.RebuildIndex().Concat(new[] { addOnFile });
            it.WriteToIndex(addOnFile);
            var actual = it.ReadIndex();
            actual.Should().BeEquivalentTo(expected);
        }
    }
}
