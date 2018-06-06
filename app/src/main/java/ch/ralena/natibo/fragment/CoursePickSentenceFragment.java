package ch.ralena.natibo.fragment;

import android.media.MediaPlayer;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;

import java.io.IOException;

import ch.ralena.natibo.MainActivity;
import ch.ralena.natibo.R;
import ch.ralena.natibo.adapter.PickSentenceAdapter;
import ch.ralena.natibo.object.Course;
import ch.ralena.natibo.object.Language;
import ch.ralena.natibo.object.Pack;
import ch.ralena.natibo.object.Sentence;
import io.realm.Realm;
import io.realm.RealmList;

public class CoursePickSentenceFragment extends Fragment {
	private static final String TAG = CoursePickSentenceFragment.class.getSimpleName();
	public static final String TAG_COURSE_ID = "course_id";

	private Language language;
	private Course course;
	private RealmList<Sentence> sentences;

	private Realm realm;

	private MediaPlayer mediaPlayer;
	private RecyclerView recyclerView;
	private SeekBar.OnSeekBarChangeListener seekBarChangeListener;
	private MenuItem checkMenu;

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_sentence_list, container, false);

		setHasOptionsMenu(true);

		realm = Realm.getDefaultInstance();
		mediaPlayer = new MediaPlayer();

		// load language and pack from database
		String courseId = getArguments().getString(TAG_COURSE_ID, null);

		course = realm.where(Course.class).equalTo("id", courseId).findFirst();
		language = course.getTargetLanguage();

		sentences = new RealmList<>();
		for (Pack pack : course.getTargetPacks()) {
			// use a sentence with index of -1 to separate the books
			Sentence sentence = new Sentence();
			sentence.setIndex(-1);
			sentence.setText(pack.getBook());
			sentences.add(sentence);
			sentences.addAll(pack.getSentences());
		}

		// load language name
		getActivity().setTitle(language.getLanguageType().getName());

		// prepare seekbar
		SeekBar seekBar = view.findViewById(R.id.seekbar);
		seekBar.setMax(sentences.size());

		seekBarChangeListener = new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				recyclerView.scrollToPosition(progress);
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {

			}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
				recyclerView.scrollToPosition(seekBar.getProgress());
			}
		};

		seekBar.setOnSeekBarChangeListener(seekBarChangeListener);

		// set up recyclerlist and adapter
		recyclerView = view.findViewById(R.id.recyclerView);
		PickSentenceAdapter adapter = new PickSentenceAdapter(language.getLanguageId(), sentences);
		recyclerView.setAdapter(adapter);
		RecyclerView.LayoutManager layoutManager = new LinearLayoutManager(getContext());
		recyclerView.setLayoutManager(layoutManager);

		adapter.asObservable().subscribe(this::playSentence);

		recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
			@Override
			public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
				super.onScrolled(recyclerView, dx, dy);
				seekBar.setOnSeekBarChangeListener(null);
				seekBar.setProgress(((LinearLayoutManager) layoutManager).findFirstVisibleItemPosition());
				seekBar.setOnSeekBarChangeListener(seekBarChangeListener);
			}

			@Override
			public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
				super.onScrollStateChanged(recyclerView, newState);
			}
		});

		return view;
	}


	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		inflater.inflate(R.menu.check_toolbar, menu);
		checkMenu = menu.getItem(0);
		checkMenu.setVisible(false);
		super.onCreateOptionsMenu(menu, inflater);
	}


	@Override
	public void onResume() {
		super.onResume();
		((MainActivity) getActivity()).setNavigationDrawerItemChecked(R.id.nav_languages);
	}

	private void playSentence(Sentence sentence) {
		checkMenu.setVisible(true);
		try {
			mediaPlayer.reset();
			mediaPlayer.setDataSource(sentence.getUri());
			mediaPlayer.prepare();
			mediaPlayer.start();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

}
