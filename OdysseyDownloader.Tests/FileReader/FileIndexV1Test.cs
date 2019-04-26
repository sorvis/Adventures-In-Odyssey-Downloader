using FluentAssertions;
using Odyssey_Downloader.Model;
using OdysseyDownloader.FileReader;
using System;
using System.Collections.Generic;
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
        public void it_should_build_index()
        {
            var cases = new[] {
                new { Expected = _scenerio.EpisodeNumbers.Select(x => x.ToString()),
                    Actual = (Func<IEnumerable<AudioFile>, IEnumerable<string>>)(input => input.Select(x => x.Number)),
                    FailMessage = $"{nameof(_scenerio.EpisodeNumbers)} did not match",
                },
                new { Expected = _scenerio.GetFileNamesCreated(),
                    Actual = (Func<IEnumerable<AudioFile>, IEnumerable<string>>)(input => input.Select(x => x.FileName)),
                    FailMessage = $"{nameof(_scenerio.GetFileNamesCreated)} did not match",
                },
                new { Expected = _scenerio.Titles,
                    Actual = (Func<IEnumerable<AudioFile>, IEnumerable<string>>)(input => input.Select(x => x.Title)),
                    FailMessage = $"{nameof(_scenerio.Titles)} did not match",
                },
                new { Expected = _scenerio.GetFormattedFileTimestamps(),
                    Actual = (Func<IEnumerable<AudioFile>, IEnumerable<string>>)(input => input.Select(x => x.Date)),
                    FailMessage = $"{nameof(_scenerio.GetFormattedFileTimestamps)} did not match",
                },
            };

            foreach (var testCase in cases)
            {
                var result = it.RebuildIndex();
                var actual = testCase.Actual(result);
                actual.Should().BeEquivalentTo(testCase.Expected, because: testCase.FailMessage);
            }
        }

        [Fact]
        public void it_should_read_back_same_as_Rebuild_created()
        {
            var expected = it.RebuildIndex();
            var actual = it.ReadIndex();
            actual.Count().Should().BeGreaterThan(0);
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
            var expected = it.RebuildIndex().Concat(new[] { addOnFile }).ToArray();
            it.WriteToIndex(addOnFile);
            var actual = it.ReadIndex().ToArray();
            actual.Should().BeEquivalentTo(expected);
        }
    }
}
