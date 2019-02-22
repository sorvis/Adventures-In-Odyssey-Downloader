using FluentAssertions;
using Odyssey_Downloader;
using SimpleFixture;
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using Xunit;

namespace OdysseyDownloader.FileReader.Tests
{
    public class FileIndexV1Test: IDisposable
    {
        private readonly FileIndexV1 it;
        private readonly TestScenerio _scenerio;

        public FileIndexV1Test()
        {
            _scenerio = new TestScenerio();
            it = new FileIndexV1(_scenerio.Config);
            _scenerio.CreateTestMp3Files();
        }

        public void Dispose()
        {
            _scenerio.Dispose();
        }

        [Fact]
        public void it_should_find_each_title()
        {
            var result = it.RebuildIndex(_scenerio.Config);
            var actualTitles = result.Select(x => x.Title);
            actualTitles.Should().BeEquivalentTo(_scenerio.Titles);
        }

        [Fact]
        public void it_should_find_each_file_name()
        {
            var result = it.RebuildIndex(_scenerio.Config);
            var actual = result.Select(x => x.FileName);
            var expectedFileNames = _scenerio.GetFileNamesCreated();
            actual.Should().BeEquivalentTo(expectedFileNames);
        }

        [Fact]
        public void it_should_find_each_episode_number()
        {
            var result = it.RebuildIndex(_scenerio.Config);
            var actual = result.Select(x => x.Number);
            var expected = _scenerio.EpisodeNumbers.Select(x => x.ToString());
            actual.Should().BeEquivalentTo(expected);
        }

        [Fact]
        public void it_should_read_back_same_as_Rebuild_created()
        {
            var expected = it.RebuildIndex(_scenerio.Config);
            var actual = it.ReadIndex();
            actual.Should().BeEquivalentTo(expected);
        }

        [Fact]
        public void it_should_know_if_index_file_exists()
        {
            it.RebuildIndex(_scenerio.Config);
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

        class TestScenerio:IDisposable
        {
            private List<string> _filesCreated = new List<string>();
            private Fixture _fixture;

            public TestScenerio()
            {
                Config = new Config();
                Config.FullPathToFiles = ".";
                _fixture = new Fixture();
            }

            public Config Config { get; set; }
            public IEnumerable<string> Titles { get; private set; }
            public List<int> EpisodeNumbers { get; } = new List<int>();

            public void CreateTestMp3Files()
            {
                Titles = new[] { "some title", "other neat show" };
                foreach(var title in Titles)
                {
                    var titleWithSpacesReplaced = title.Replace(' ', '_');
                    var episodeNumber = Convert.ToInt32(_fixture.Generate<uint>() / 2);
                    EpisodeNumbers.Add(episodeNumber);
                    createFile(titleWithSpacesReplaced, Convert.ToString(episodeNumber));
                }
            }

            public IEnumerable<string> GetFileNamesCreated()
            {
                return _filesCreated.Select(x => Path.GetFileName(x));
            }

            public void Dispose()
            {
                foreach(var file in _filesCreated)
                {
                    File.Delete(file);
                }
                _filesCreated.Clear();
                var indexFullFileName = Config.GetIndexFilePath();
                if (File.Exists(indexFullFileName)) File.Delete(indexFullFileName);
            }

            private void createFile(string title, string number)
            {
                var fileName = $"{number}#-{title}.mp3";
                using (File.Create(fileName)) { }
                _filesCreated.Add(fileName);
            }
        }
    }
}
