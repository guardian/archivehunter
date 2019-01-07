import React from 'react';
import PropTypes from 'prop-types';

class FilterButton extends React.Component {
    static propTypes = {
        fieldName: PropTypes.string.isRequired,
        values: PropTypes.object.isRequired,
        isActive: PropTypes.boolean,
        onActivate: PropTypes.func,
        onDeactivate: PropTypes.func,
        type: PropTypes.object.isRequired /* 'plus' (filter in) or 'minus' (filter out) */
    };

    constructor(props){
        super(props);
        this.state = {
            active: false
        };

        this.filterIconClicked = this.filterIconClicked.bind(this);
        this.filterChangedCB = this.filterChangedCB.bind(this);
    }

    componentWillMount() {
        if(typeof(this.props.isActive)!=="undefined") this.setState({active: this.props.isActive});
    }

    filterChangedCB() {
        /* called once the filter state has changed, to trigger props callbacks. note that since the state has now been changed,
        onActivate triggers when state==active
         */
        if(this.state.active){
            if(this.props.onActivate) this.props.onActivate(this.props.fieldName, this.props.values, this.props.type);
        }
        if(!this.state.active){
            if(this.props.onDeactivate) this.props.onDeactivate(this.props.fieldName, this.props.values, this.props.type);
        }
    }

    filterIconClicked(event) {
        this.setState({active: !this.state.active}, this.filterChangedCB);
    }

    className() {
        if(this.state.active) {
            return "fa control-icon"
        } else {
            return "fa fa-search-"+ this.props.type +" control-icon"
        }
    }

    render() {
        /* className="fa fa-search-plus */
        if(!this.props.values) return <i/>;   //don't render anything if the value is blank
        return <i className={this.className()} onClick={this.filterIconClicked}/>
    }
}

export default FilterButton;