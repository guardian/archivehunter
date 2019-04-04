import React from 'react';
import PropTypes from 'prop-types';
import {Pie} from 'react-chartjs-2';

class GeneralOverviewChart extends React.Component {
    static propTypes = {
        recentCountData: PropTypes.object.isRequired,
        dataSeriesClicked: PropTypes.func
    };

    static makeColourValues(count, offset){
        let values = [];
        for(let n=0;n<count;++n){
            let hue = (n/count)*360.0 + offset;
            values[n] = 'hsla(' + hue + ',75%,50%,0.6)'
        }
        return values;
    }

    static colourValues = GeneralOverviewChart.makeColourValues(20,10);

    constructor(props){
        super(props);

        this.state = {
            chartData: null,
            dataSetNames: []
        }
    }

    componentWillMount() {
        this.updateChartData();
    }

    componentDidUpdate(prevProps, prevState, snapshot) {
        if(prevProps.recentCountData!==this.props.recentCountData) this.updateChartData();
    }

    /**
     * updates the ChartJS format of data from the server-format data provided in props
     */
    updateChartData(){
        if(this.props.recentCountData) {
            const unpackedData = Object.keys(this.props.recentCountData)
                .filter(key=>typeof(this.props.recentCountData[key])==="number" && key!=="grandTotal")
                .map(key=>{return {key: key, count: this.props.recentCountData[key]}});

            this.setState({
                dataSetNames: unpackedData.map(item => item.key),
                chartData: {
                    labels: unpackedData.map(item => item.key),
                    datasets: [{
                        label: "Problem items",
                        data: unpackedData.map(item => item.count),
                        backgroundColor: GeneralOverviewChart.colourValues
                    }],

                }
            });
        }
    }

    render(){
        return this.state.chartData ? <Pie
            data={this.state.chartData}
            height={400}
            width={200}
            getElementsAtEvent={elems=>{
                console.log(elems);
                if(this.props.dataSeriesClicked){
                    const clickedName = this.state.dataSetNames[elems[0]._index];
                    console.log("Clicked " + clickedName);
                    this.props.dataSeriesClicked(clickedName);
                }
            }}
            options={{
                title: {
                    display: true,
                    text: "Overview",
                    fontColor:"rgba(255,255,255,1)",
                    fontSize: 24
                },
                maintainAspectRatio: false,
                legend: {
                    display: true,
                    labels: {
                        fontColor: "rgba(255,255,255,1)"
                    },
                    position: "bottom"
                }
            }}
        /> : <div>loading...</div>
    }
}

export default GeneralOverviewChart;