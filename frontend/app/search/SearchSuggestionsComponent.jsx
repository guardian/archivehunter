import React from 'react';
import PropTypes from 'prop-types';
import axios from 'axios';
import ErrorViewComponent from '../common/ErrorViewComponent.jsx';
import Expander from '../common/Expander.jsx';


class SearchSuggestionsComponent extends React.Component {
    static propTypes = {
        terms: PropTypes.string.isRequired,
        autoHide: PropTypes.bool
    };

    constructor(props){
        super(props);

        this.state = {
            loading: false,
            error: null,
            suggestionsList: [],
            expanded: false
        };

        this.reloadData = this.reloadData.bind(this);
        this.toggleExpanded = this.toggleExpanded.bind(this);
    }

    componentDidUpdate(oldProps,oldState){
        if(oldProps.terms!==this.props.terms) this.reloadData();
    }

    reloadData(){
        console.log(this);

        this.setState({
            loading: true,
            error: null,
            suggestionsList: []
        }, ()=> {
            axios.put("/api/search/suggestions", this.props.terms, {headers: {'Content-Type': 'text/plain'}})
                .then(result => {
                    console.log(result);
                    this.setState({loading: true, error: null, suggestionsList: result.data.suggestions})
                })
                .catch(err => {
                    console.error(err);
                    this.setState({loading: false, error: err, suggestionsList: []})
                })
        });
    }

    toggleExpanded(newValue){
        this.setState({expanded: newValue});
    }

    render(){
        if(this.state.error){
            return <ErrorViewComponent error={this.state.error}/>
        }
        return <div className="suggestions-container" style={{display: this.props.autoHide && this.state.suggestionsList.length===0 ? "none" : "block"}}>
            <Expander expanded={this.state.expanded} onChange={this.toggleExpanded}/><h4 style={{cursor: "pointer"}} onClick={()=>this.toggleExpanded(!this.state.expanded)}>Suggested terms</h4>
            <ul className="suggestions-list" style={{display: this.state.expanded ? "block": "none"}}>
            {
                this.state.suggestionsList.map(entry=><li className="suggestions-list">{entry}</li>)
            }
            </ul>
        </div>
    }
}

export default SearchSuggestionsComponent;